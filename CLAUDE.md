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
4. Keep older entries unless they are clearly obsolete; append instead of rewriting history unless the file needs cleanup.
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
