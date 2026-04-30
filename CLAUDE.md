# RedditTLDR

Android app that draws a floating bubble over the Reddit app. Tap -> extract the visible post via `AccessibilityService` -> Claude API summary -> overlay card. Optional second tap on the card summarizes the post's comments via Reddit's anonymous JSON endpoint.

Package: `com.stanley.reddittldr`. Target Reddit app: `com.reddit.frontpage` (also accepts any `com.reddit.*` prefix).

## Stack

- Kotlin, minSdk 26, targetSdk 34
- Gradle Kotlin DSL with version catalog
- Compose for the in-app settings screen; classic Views for all overlay UI (`Compose` does not work well with `TYPE_APPLICATION_OVERLAY`)
- Coroutines, OkHttp, kotlinx.serialization
- `EncryptedSharedPreferences` for the Claude API key
- `FLAG_SECURE` on the settings activity
- Default Claude model: `claude-haiku-4-5-20251001`

## Architecture

Three runtime pieces:

1. **`RedditWatcherService`** (`AccessibilityService`)
   Watches `TYPE_WINDOW_STATE_CHANGED`. When Reddit comes to the foreground it starts `BubbleService`; when Reddit leaves it stops it. Holds a `userDismissed` flag that survives intra-Reddit navigation and resets only when the user genuinely leaves Reddit and returns. Owns the only public entry point for reading the post: `extractCurrentPost()`.

2. **`BubbleService`** (foreground `Service`)
   Owns the floating bubble window, dismiss target, and summary overlay. On tap: spinner -> call `extractCurrentPost()` -> `ClaudeRepository.summarize(...)` -> mount `SummaryOverlay`.

3. **`PostExtractor`**
   Single-strategy reader of the accessibility tree. No web requests, no fallbacks.

Settings UI is a regular `MainActivity` with Compose; the user enters their Claude API key and picks model plus summary length there.

## File Map

```text
app/src/main/java/com/stanley/reddittldr/
|-- MainActivity.kt                    # Settings screen host
|-- api/
|   |-- ClaudeRepository.kt            # summarize() + summarizeComments()
|   `-- models/                        # ClaudeRequest, ClaudeResponse
|-- data/
|   |-- ClaudeModel.kt                 # Model enum (haiku/sonnet/opus IDs)
|   |-- SettingsRepository.kt          # EncryptedSharedPreferences-backed
|   `-- SummaryLength.kt
|-- reddit/
|   |-- PostContent.kt                 # data class - extraction result
|   |-- PostExtractor.kt               # single-strategy screen reader
|   `-- RedditJsonClient.kt            # fetchComments + searchPostId (anon)
|-- service/
|   |-- BubbleService.kt               # bubble lifecycle + summary orchestration
|   `-- RedditWatcherService.kt        # accessibility service
|-- ui/
|   |-- OnboardingSection.kt
|   |-- SettingsScreen.kt
|   `-- overlay/
|       |-- BubbleView.kt              # draggable circle
|       |-- DismissScrimView.kt
|       |-- DismissTargetView.kt
|       `-- SummaryOverlay.kt          # the summary card
`-- util/
    |-- DebugLog.kt                    # per-session in-memory log + file flush
    `-- PermissionState.kt
```

## Post Extraction

Lives in `reddit/PostExtractor.kt`. The earlier three-strategy fallback chain (Compose semantic nodes / `comments/{id}.json` / scroll-scrape) was removed because the JSON path failed on many real posts and the Compose path was too fragile against Reddit version bumps. The current approach is one strategy: walk Reddit's accessibility windows, read visible text, scroll down, repeat.

Flow inside `extract()`:

1. `redditRoots()` - collect every `service.windows` root whose package matches `com.reddit.*`. Reddit's modern UI is laid out across multiple sibling windows (toolbar overlay, body container, comment sheet, and so on). Reading just one gives mostly chrome.
2. `findPostIdAcross(roots)` - best-effort base36 ID lookup from view tags and hyperlinks. Used later for the comments API.
3. `readFromScreen(postId)` - collect visible text top-to-bottom, scroll down with `ACTION_SCROLL_DOWN` until either a comment-section marker appears (with a min-lines guard) or no new content arrives, then scroll back up. Emit `PostContent` with `linesCaptured` and `forwardScrolls` populated for the UI footer.

`ExtractionMethod` is `SCREEN`, `SCREEN_SCROLLED`, or `FAILED`.

### Hard-Won Extraction Rules

| Pitfall | Rule |
|---|---|
| `rootInActiveWindow` returns the focused window. When the bubble is tapped, that is our overlay, not Reddit. | Always go through `service.windows` and filter by package. Never use `rootInActiveWindow`. |
| `flagRetrieveInteractiveWindows` plus `canRetrieveWindowContent="true"` are required for `service.windows` to return anything. | These live in `res/xml/accessibility_service_config.xml`. Do not remove them. |
| `ACTION_SCROLL_FORWARD` matches horizontal pagers (Reddit's swipe-between-posts container). Using it scrolls to the next post mid-extraction. | Use `AccessibilityAction.ACTION_SCROLL_DOWN.id` only. Vertical-only. |
| Reddit's toolbar shows a `Comments` button. The sticky `Join the conversation` / `Add a comment` bar is always visible. Tripping comment-boundary detection on these aborts before reading the body. | `COMMENT_MARKERS` is restricted to `sort by:`, `sort comments`, `be the first to comment`, `no comments yet`, plus `MIN_LINES_BEFORE_COMMENT_STOP = 8`. |
| `looksLikeChrome` substring matching ate real titles (`subscription` contains `subscribe`). | Use a word-boundary regex and only apply it to short lines. |
| Sidebar promo subreddits got picked up by a flat regex over the whole tree. | Detect subreddit by scanning top-to-bottom visible text and taking the first `r/<sub>` match. |
| Lazy-list ghost nodes hang around the tree off viewport. | Filter every collected node by `Rect.intersects(nodeBounds, viewport)`. |
| Our own bubble, summary, and dismiss overlays emit `TYPE_WINDOW_STATE_CHANGED`. Reacting to them tore the summary down right after it appeared. | Ignore events where `event.packageName == packageName`. |
| Transient popups (IME, permission dialogs) made `RedditWatcherService` think Reddit was gone. | Check `isRedditForeground()` before stopping the bubble. |

## Summary Card UX

Lives in `ui/overlay/SummaryOverlay.kt`.

- Scrollable post-summary body
- Footer note below the body: `Captured N lines across M screens`
- `Summarize comments` button when the app can identify the post directly or via subreddit plus title search
- Inline comments summary appended to the same scroll view
- Copy button copies post summary plus comments summary as plain text
- The card lives in a `TYPE_APPLICATION_OVERLAY` window with a tap-outside-to-dismiss scrim

## Reddit JSON Client

Anonymous use of `https://www.reddit.com/...json`. Required header:

- `User-Agent: RedditTLDR/1.0 (Android; by /u/stanley)`

Endpoints used:

- `fetchComments(postId, limit)` -> `comments/{id}.json`
- `searchPostId(subreddit, title)` -> `r/{sub}/search.json?restrict_sr=on&q=...`

## Build and Sideload

JBR (Android Studio's bundled JDK) is required for Gradle:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/RedditTLDR-debug.apk
```

The `~/Downloads/RedditTLDR-debug.apk` location is the established sideload convention.

## Debug Logging

`util/DebugLog.kt` keeps a per-session in-memory ring and flushes to `filesDir/reddittldr_debug.log`. The settings screen has a `Debug logs` action that surfaces it. When extraction misbehaves, the log shows fields like `redditRoots=N`, `childCounts=...`, `postId=...`, `forwardScrolls=N`, `hitComments=true/false`, and `result=...`.

If you change the extractor, keep the `DebugLog.logKv(...)` calls in place. Without them, diagnosing extraction failures gets much harder.

## Things That Have Been Tried and Removed

- `comments/{id}.json` as the primary extractor
- Compose semantic-node walking by resource ID
- Sharing into the app via the share sheet
- Reading from a single Reddit window root

## Agent Memory Protocol

Use this file as the standing project memory.

Rules:

1. Any meaningful code change, architecture decision, bug fix, regression, or workflow adjustment should be recorded here.
2. Each note should be prefixed with the agent name in brackets, for example `[Claude]` or `[Codex]`.
3. Prefer concise entries that explain what changed, why it changed, and any follow-up risk.
4. Keep older entries, including failed attempts and mistakes. Do not rewrite agent log history; append corrections and follow-up outcomes instead.
5. If the file's encoding gets damaged, normalize it back to plain ASCII or valid UTF-8 while preserving meaning.

Suggested note format:

```text
### YYYY-MM-DD
- [AgentName] What changed. Why it changed. Any follow-up note.
```

## Agent Log

### 2026-04-26
- [Claude] Built the initial RedditTLDR Android app: accessibility-driven Reddit post extraction, floating bubble service, summary overlay, optional comment summarization via Reddit JSON, encrypted Claude API key storage, and debug logging support.
- [Claude] Documented the extractor rules and platform-specific constraints in this file so later agents would know what not to regress.
- [Codex] Fixed `BubbleService` startup order so the app checks overlay permission before entering foreground-service mode, and stops immediately if it cannot show the bubble.
- [Codex] Unified Reddit package detection in `RedditWatcherService` by routing both event handling and foreground checks through one package-matching rule.
- [Codex] Cleaned visible UI text in `strings.xml`, `SettingsScreen.kt`, and `SummaryOverlay.kt` to remove mojibake and keep the interface stable.
- [Codex] Established the agent-tagged memory-file convention in this project so future work can show who changed what and why.
- [Codex] Attempted to make `PostExtractor` scroll more reliably by adding a fallback from explicit vertical scrolling to generic forward/backward actions on tall containers, plus a short settle retry after scrolling.
- [Codex] Correction: removed the generic forward/backward fallback immediately after device testing showed it could page horizontally into adjacent Reddit posts. Kept the safer improvement: a brief post-scroll settle retry while staying strictly on explicit vertical `SCROLL_DOWN`/`SCROLL_UP`.
- [Claude] Hardened `.gitignore` to cover `.kotlin/`, `**/build/`, `*.log`, all keystore types (`*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key`, `keystore.properties`), `*.env`, and `google-services.json`. Original ignore only matched root-level `/build` and missed the Kotlin daemon cache.
- [Claude] Initialized git repo and pushed to `https://github.com/stanley-projects/RedditTLDR` (private). Verified pre-push that `local.properties` is not staged and no hardcoded secrets exist anywhere in `app/src`. Default branch `main`.
- [Claude] Diagnosed why posts were always summarized off the first screen only: every test session showed `forwardScrolls=1, totalLines unchanged, hitComments=true`. Hypothesis from code reading: `findScrollableAcross` picked the largest scrollable by area, which on Reddit's post screen is often the comments LazyColumn — `ACTION_SCROLL_DOWN` then scrolled comments while the post body stayed put, bringing "Sort by:" into view and tripping the comment-marker boundary on the very first iteration. Codex's settle/stagnant logic could not help because `hitComments` was set in the first `mergeOnce` after the scroll, skipping the retry path.
- [Claude] Replaced `ACTION_SCROLL_DOWN` with `dispatchGesture` swipe in `PostExtractor.kt`. Removed `findScrollableAcross` and the `ScrollTarget` data class. New `scrollByGesture(scrollDown)` swipes vertically through the screen center for ~55% of the viewport (78% → 23%) over 350ms, awaiting completion via `suspendCancellableCoroutine` + `GestureResultCallback`. Bypasses scrollable selection entirely; controlled distance prevents jumping past the "Sort by:" marker (which would otherwise add comment text as body). Backward restore uses the inverse swipe. Bubble lives at a screen edge, gesture is centered, so no overlay collision. Kept Codex's settle-retry and stagnant-tolerance for cases where the swipe is delivered but content hydration is delayed.
- [Claude] First sideload of the gesture-based scroll hung — `Job was cancelled` after 22-58s with no `[read-scroll]` line ever logged. Cause: `dispatchGesture(gesture, callback, null)` posts the callback to the calling thread's looper. Extraction runs on `Dispatchers.Default` which has no looper, so the callback was silently dropped and `suspendCancellableCoroutine` never resumed. **Fix:** added `private val mainHandler = Handler(Looper.getMainLooper())` field on `PostExtractor` and pass it as the third arg to `dispatchGesture`. Always pass an explicit looper-bound Handler when dispatching gestures from background threads.

### 2026-04-28
- [Claude] Second sideload still hung the same way (`initialLines=20 hitComments=false` then 41s of nothing). The handler fix was correct but insufficient — the actual cause was that `dispatchGesture` requires the service to declare the gesture-dispatch capability, which `accessibility_service_config.xml` did not. Without the capability the system either silently no-ops or accepts the call without ever invoking the callback. **Fix:** added `android:canPerformGestures="true"` to `accessibility_service_config.xml`. Important: AccessibilityServiceInfo is cached by the system on bind — after sideloading the new APK, the user must toggle the service off and on in Settings → Accessibility for the new capability to take effect. Also added a 3-second `withTimeoutOrNull` around the gesture await so a future failure can never hang the whole extraction again — it will return false and break the scroll loop with a timeout log entry. Added a `[scroll-gesture] dispatched=true/false` log immediately after the dispatch call so we can see whether the gesture was at least accepted.
- [Claude] Confirmed working after the user toggled the accessibility service in Settings to pick up the new capability. Long-post extraction now scrolls correctly via the dispatched swipe.
- [Claude] Comments-summary feature was returning bullets that didn't match the comments visible on the post. Two probable causes: (1) `findPostIdAcross` returns `<none>` on every modern-Reddit screen, so we always fall back to `searchPostId`, whose 60% word-overlap threshold was loose enough to match a different post with a similar title and we'd faithfully summarize the wrong post's comments — looking exactly like fabrication. (2) Comments system prompt didn't forbid invention. **Fixes:** raised `searchPostId` threshold to 75 (3-of-4 significant words). Tightened `COMMENTS_SYSTEM_PROMPT` to "stick strictly to what is in the input — do not invent opinions" and "if comments are sparse or repetitive, write fewer bullets — do not pad". Threaded the fetched post's actual title back through to the summary card; footer now shows `Summarized N comments from: "<title>"`. Added `CommentsResult(sourceTitle, sourcePermalink, comments)` data class to `RedditJsonClient`, `CommentsSummaryResult(summary, count, sourcePostTitle)` to `SummaryOverlay`, and `[redditJson] sourceTitle=…, topComment="…"` debug log so the next mismatch is diagnosable from the log alone. **Verification:** if a wrong-post match still slips through above the 75 threshold, the user will see the wrong title in the footer and we'll know exactly which path failed.
- [Claude] User reported the gesture-based extractor was always doing exactly 2 scrolls regardless of post length — too many for short posts (post fits, no scrolling needed) and too few for long posts (only 2 of N viewports captured). Cause: `MAX_STAGNANT_SCROLLS = 2` was firing because `ordered.size` not growing was being treated as "stagnant", but it can't tell the difference between "page didn't move" and "page moved but visible content was duplicates of what we'd already collected". The dispatched gesture itself was likely not moving the page at all on Reddit's Compose UI — gesture absorbed somewhere or the touched area not registering as scrollable. **Fix (in `PostExtractor.kt`):** (a) Replaced stagnant detection with a fingerprint hash of the visible-text set — if the fingerprint is identical before and after a scroll attempt, the page genuinely didn't move and we break immediately (one bail attempt instead of two). If the fingerprint changes, we keep scrolling regardless of whether `ordered` grew. (b) Added `findBodyScrollable(roots, anchor)` that picks the scrollable whose subtree contains the post heading text (or longest body line as fallback) — this is now the primary scroll mechanism, with the dispatched gesture only as fallback if no anchored scrollable is found. Resolves the "scrolling the wrong widget" issue that the largest-by-area heuristic always had on Reddit. (c) Removed `MAX_STAGNANT_SCROLLS` entirely. (d) Bumped `SCROLL_WAIT_MS`, `EXTRA_SCROLL_SETTLE_MS`, and `GESTURE_DURATION_MS` to 500 ms each — Reddit's lazy-list hydration and slower-velocity gestures (less fling) both seem to want more time. New log fields: `method=anchored-action|gesture`, `moved=true|false`, `addedLines=N`, `usedGestureFallback=true|false`.
- [Claude] First post-fix logs: anchored scroll is working (`method=anchored-action ok=true moved=true` on every iteration). All 4 test sessions exit cleanly after 1 scroll with `hitComments=true` because the post body was already captured in the initial visible-text read; the single scroll just confirmed we'd reached the "Sort by:" boundary. Body captures ranged 955-2399 chars. Same post tapped at different scroll positions yields different `bodyLen` (initial read sees whatever is on screen at tap time).
- [Claude] User reported "super long post but it only did 2 scrolls, missing content". Diagnosed two compounding causes from the log: (1) user's `length=SHORT` setting → "Summarize this Reddit post in 1-2 sentences" prompt, which obviously omits most details from a 2399-char body. (2) Extractor reads forward from wherever the user is on screen at tap time — if user scrolled into the post before tapping, the top portion of the body is never captured. **Fix:** Added a Phase-1 scroll-UP loop in `readFromScreen` that runs before the existing forward loop. Uses the same `findBodyScrollable(anchor)` mechanism so it targets the post container (not the comments LazyColumn or pull-to-refresh), with the same fingerprint-based stop condition as the forward loop. Skipped when no anchor is found (gesture fallback risks triggering pull-to-refresh / back-nav) or when `hitComments` was already true on initial read (user tapped from inside comments). Updated the restore logic to handle both directions: net displacement is `forwardCount - upScrolls`, restored by scrolling the inverse. Result: extraction now captures the full body regardless of where the user tapped within the post. New log fields: `[read-up] moved=true|false addedLines=N scrollIndex=N`, `[read] upScrolls=N linesAfterUp=N`.
- [Claude] Above fix didn't work — user verified by placing a sentinel marker at the bottom of a long post; the summary never mentioned it. Real root cause: `ACTION_SCROLL_DOWN` on Reddit's body container scrolls the **entire body in one step**, not one viewport. So even with the up-then-down phase structure, the down phase took one giant jump, hit the comments boundary, and exited — middle of the body never passed through the read window. The page was visibly scrolling all the way to the bottom in a single sweep, so the user could see content move past while the extractor only ever read what was on screen at the start and end. Patches over patches were never going to fix this. **Rewrite of `PostExtractor.kt`:** torn out `findBodyScrollable`, the anchored-action / gesture fallback hierarchy, the `usedGestureFallback` log field, and the multiple stagnant-detection schemes. Replaced with a single scroll primitive `scrollOneStep(direction)` that dispatches a controlled-distance swipe (~50% of viewport, 78% → 28%) over 500 ms, then verifies the page actually moved via fingerprint comparison. The half-viewport step size is the whole point — it gives every part of the body a chance to be in the read window between scrolls, instead of getting jumped over by a widget-defined "page". `readFromScreen` is now three explicit phases: initial read → walk to top → walk to bottom (stops on comment marker or no-movement) → restore. `MAX_SCROLL_ITERATIONS` raised to 15 to handle long posts at half a viewport per step. The architecture matches the user's mental model: incremental progress, per-step stop decision, hard safeguard when nothing changes.
- [Claude] First sideload of the rewrite: `[scroll] direction=UP moved=false` and `direction=DOWN moved=false` for both attempts — gestures dispatched but the page didn't move. Cause: the bubble overlay window has tap/drag handlers and is therefore touchable. When `dispatchGesture` fires at the screen-center column and the bubble sits anywhere along that vertical line (and the user can drag it anywhere), the bubble absorbs the touch sequence and Reddit never receives it. **Fix in `BubbleService.kt`:** added `setBubbleTouchable(touchable: Boolean)` that toggles `FLAG_NOT_TOUCHABLE` on the bubble's WindowManager LayoutParams via `updateViewLayout`. Called with `false` immediately after `bv.setLoading(true)`, restored to `true` in the coroutine's `finally` block (and in `failWithToast` for early-exit paths). With `FLAG_NOT_TOUCHABLE`, touches at the bubble's coordinates pass through to the next window down (Reddit), so dispatched swipes reach the post. The bubble stays visible and shows the spinner — only its touch interception is suspended for the ~1-3 seconds of extraction.

### 2026-04-29
- [Claude] User scrapped the "smart stop" approach entirely and gave a deterministic spec: every bubble tap walks up to the post top (cap **5** scrolls) then down through the body **and** the comments (cap **6** scrolls), capturing everything in one pass; comments are then summarized from that captured pool instead of via a second Reddit-JSON round-trip; nothing persisted beyond the summary card's lifetime. Caps exist purely to bound runaway scrolling on accidental feed-page taps. **Implementation:** rewrote `readFromScreen` as three explicit phases — Phase 1 (no-capture up scroll up to `MAX_UP_SCROLLS=5`), Phase 2 (read+capture, scroll down up to `MAX_DOWN_SCROLLS=6`, no longer exits on the comment marker), Phase 3 (split the captured visual-order list at the first comment marker into body and comments). Removed the `MAX_SCROLL_ITERATIONS` and `MIN_LINES_BEFORE_COMMENT_STOP` constants. Added `capturedCommentsText: String` to `PostContent`. `ClaudeRepository.summarizeComments` now takes that text blob directly (no `List<Comment>` plumbing); the prompt explains the input is screen-captured and tells the model to ignore interleaved chrome. `BubbleService.showSummary` no longer calls `RedditJsonClient.searchPostId` / `fetchComments` — the comments callback uses `post.capturedCommentsText` directly. `RedditJsonClient` is left in the codebase but no longer wired into the comments path. Net effect: predictable bounded behavior (≤11 swipes per tap), no anonymous-API dependency for comments, no possibility of summarizing the wrong post's comments.
- [Claude] First sideload of the deterministic spec: scrolling worked perfectly (`6 down scrolls all moved=true`, `lines=122` captured), but `result=FAIL bodyLen=18`. The split-at-first-marker was firing too early in the captured list — almost no body survived. Cause: I deleted the `MIN_LINES_BEFORE_COMMENT_STOP` guard during the rewrite, but the post-screen chrome (avatar/menu/vote-bar elements) can contain text that matches our marker prefixes (`"sort by:"`, `"sort comments"`, `"be the first to comment"`, `"no comments yet"`), and a false-positive hit at line 1-3 truncates the body. **Fix:** restored `MIN_LINES_BEFORE_COMMENT_STOP = 8` and applied it to the post-capture split: `markerIdx` is now the first index where `idx >= 8 && looksLikeCommentMarker(line)`. Also added failure-path diagnostics (`markerIdx`, `markerLine`, `bodyLinesSnippet`) and the same fields to the success path so the next mismatch is diagnosable from the log.
- [Claude] Next test: scrolling and capture both fine, but the post-summary card showed the post AND comments mixed together ("...with commenters affirming it was worthwhile..."), and the "Summarize comments" button never appeared. Cause: the post had real comments, but the marker-list approach (`"sort by:"`, etc.) didn't match Reddit's current comment-section header text — so the split returned `markerIdx = -1`, everything went into `body`, `capturedCommentsText` was blank, and the comments button check (`isBlank()`) hid the button. **Architecture change:** stopped trying to split body from comments at extraction time. Removed `capturedCommentsText` from `PostContent`, removed the marker logic and `MIN_LINES_BEFORE_COMMENT_STOP` and `looksLikeCommentMarker` and `COMMENT_MARKERS` from `PostExtractor`. Now `body` holds the entire captured screen content. **Both summarize calls send that whole blob; the prompts do the differentiation:** `systemPromptFor` gains a leading paragraph telling the model the input includes comment-section text after the post body and to ignore it entirely, summarizing only what the OP wrote. `summarizeComments` gets a re-written prompt that tells the model to focus on the comments and explicitly say so plainly if no comments are present. Comments button visibility is now a simple length heuristic (`COMMENTS_BUTTON_MIN_BODY_LEN = 500`) — if we captured anything substantial, the button shows; tapping it always produces a useful response (either the discussion summary or a "no comments to summarize" message). Footer text simplified to "Comments summary" since we no longer have a precise comment count. Also dropped `count` and `sourcePostTitle` semantics from the `CommentsSummaryResult` display (count was always 0 anyway with the new model).
- [Claude] User confirmed end-to-end working: post summary contains only the post body (no comments bleed-through), "Summarize comments" button appears under the summary, and tapping it produces a separate comments-focused summary. The deterministic 5-up + 6-down scroll spec, prompt-based body/comment differentiation, and bubble-non-touchable-during-extraction together resolved the long-running scroll/capture/summary regressions.
