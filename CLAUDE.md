# RedditTLDR

Android app that draws a floating bubble over the Reddit app. Tap → extract the visible post via AccessibilityService → Claude API summary → overlay card. Optional second tap on the card summarizes the post's comments via Reddit's anonymous JSON endpoint.

Package: `com.stanley.reddittldr`. Target Reddit app: `com.reddit.frontpage` (also accepts any `com.reddit.*` prefix).

## Stack

- Kotlin, minSdk 26, targetSdk 34
- Gradle Kotlin DSL with version catalog
- Compose for the in-app settings screen; **classic Views** for all overlay UI (Compose does not work well with `TYPE_APPLICATION_OVERLAY`)
- Coroutines, OkHttp, kotlinx.serialization
- `EncryptedSharedPreferences` for the Claude API key. `FLAG_SECURE` on settings activity.
- Default Claude model: `claude-haiku-4-5-20251001`. Alternatives in `data/ClaudeModel.kt`.

## Architecture

Three runtime pieces:

1. **`RedditWatcherService`** (AccessibilityService) — watches `TYPE_WINDOW_STATE_CHANGED`. When Reddit comes to the foreground it starts `BubbleService`; when Reddit leaves it stops it. Holds a `userDismissed` flag that survives intra-Reddit nav and resets only when the user genuinely leaves Reddit and returns. Owns the only public entry point for reading the post: `extractCurrentPost()`.

2. **`BubbleService`** (foreground Service) — owns the floating bubble window, the dismiss target, and the summary overlay. On tap: spinner → call `extractCurrentPost()` → `ClaudeRepository.summarize(...)` → mount `SummaryOverlay`.

3. **`PostExtractor`** — single-strategy reader of the accessibility tree. No web requests, no fallbacks.

Settings UI is a regular `MainActivity` with Compose; the user enters their Claude API key and picks model + summary length there.

## File map

```
app/src/main/java/com/stanley/reddittldr/
├── MainActivity.kt                    # Settings screen host
├── api/
│   ├── ClaudeRepository.kt            # summarize() + summarizeComments()
│   └── models/                        # ClaudeRequest, ClaudeResponse
├── data/
│   ├── ClaudeModel.kt                 # Model enum (haiku/sonnet/opus IDs)
│   ├── SettingsRepository.kt          # EncryptedSharedPreferences-backed
│   └── SummaryLength.kt
├── reddit/
│   ├── PostContent.kt                 # data class — extraction result
│   ├── PostExtractor.kt               # single-strategy screen reader
│   └── RedditJsonClient.kt            # fetchComments + searchPostId (anon)
├── service/
│   ├── BubbleService.kt               # bubble lifecycle + summary orchestration
│   └── RedditWatcherService.kt        # accessibility service
├── ui/
│   ├── OnboardingSection.kt
│   ├── SettingsScreen.kt
│   └── overlay/
│       ├── BubbleView.kt              # draggable circle
│       ├── DismissScrimView.kt
│       ├── DismissTargetView.kt
│       └── SummaryOverlay.kt          # the summary card
└── util/
    ├── DebugLog.kt                    # per-session in-memory log + file flush
    └── PermissionState.kt
```

## Post extraction (the hard part)

Lives in `reddit/PostExtractor.kt`. Replaced an earlier 3-strategy fallback chain (Compose semantic nodes / `comments/{id}.json` / scroll-scrape) — that chain looked clever but the JSON path failed on most posts and the Compose path was fragile to Reddit version bumps. **Current approach is one strategy: walk Reddit's accessibility windows, read visible text, scroll down, repeat.**

Flow inside `extract()`:

1. `redditRoots()` — collect every `service.windows` root whose package matches `com.reddit.*`. Reddit's modern UI is laid out across **multiple sibling windows** (toolbar overlay, body container, comment sheet…). Reading just one gives 4 lines of chrome. Walk them all together.
2. `findPostIdAcross(roots)` — best-effort base36 ID lookup from view tags / hyperlinks. Used later for the comments API.
3. `readFromScreen(postId)` — collect visible text top-to-bottom, scroll down with `ACTION_SCROLL_DOWN` until either a comment-section marker appears (with a min-lines guard) or no new content arrives, then scroll back up. Emit `PostContent` with `linesCaptured` and `forwardScrolls` populated for the UI footer.

`ExtractionMethod` is `SCREEN`, `SCREEN_SCROLLED`, or `FAILED`.

### Hard-won extraction rules — do not regress these

| Pitfall | Rule |
|---|---|
| `rootInActiveWindow` returns the **focused** window. When the bubble is tapped, that's our overlay, not Reddit. | Always go through `service.windows` and filter by package. Never `rootInActiveWindow`. |
| `flagRetrieveInteractiveWindows` + `canRetrieveWindowContent="true"` are required for `service.windows` to return anything. | These live in `res/xml/accessibility_service_config.xml` — don't remove. |
| `ACTION_SCROLL_FORWARD` matches **horizontal** pagers (Reddit's swipe-between-posts container). Using it scrolls to the next post mid-extraction. | Use `AccessibilityAction.ACTION_SCROLL_DOWN.id` only. Vertical-only. |
| Reddit's toolbar shows a "Comments" button. The sticky "Join the conversation" / "Add a comment" bar is always visible. Tripping comment-boundary detection on these aborts before reading any body. | `COMMENT_MARKERS` is restricted to `"sort by:"`, `"sort comments"`, `"be the first to comment"`, `"no comments yet"`. Plus a `MIN_LINES_BEFORE_COMMENT_STOP = 8` guard. |
| `looksLikeChrome` substring matching ate real titles ("subscription" contains "subscribe"). | Word-boundary regex (`\bsubscribe\b`) **and** only apply to lines ≤40 chars. |
| Sidebar promo subreddits got picked up by a flat regex over the whole tree (`r/<sub>`). | Detect subreddit by scanning the **top-to-bottom ordered** visible text and taking the first `r/<sub>` match — it's the post's own subreddit. |
| Lazy-list ghost nodes hang around the tree off-viewport. | Filter every collected node by `Rect.intersects(nodeBounds, viewport)`. |
| Our own bubble / summary / dismiss overlays emit `TYPE_WINDOW_STATE_CHANGED`. Reacting to them tore the summary down milliseconds after it appeared. | Ignore events where `event.packageName == packageName`. |
| Transient popups (IME, permission dialogs) made `RedditWatcherService` think Reddit was gone. | Check `isRedditForeground()` (walks `service.windows` for any `com.reddit.*` root) before stopping the bubble. |

## Summary card UX (`ui/overlay/SummaryOverlay.kt`)

- Scrollable post-summary body.
- Footer note below the body: `Captured N lines across M screens` — lets the user verify the extractor actually scrolled. Suppressed when `linesCaptured == 0`.
- "Summarize comments" button in the footer row, shown when we have either a direct postId or a `subreddit + title` we can search with. On tap it fetches comments via `RedditJsonClient.fetchComments(...)` and calls `ClaudeRepository.summarizeComments(...)`. The result appends below the post summary in the same scroll view, with a `Summarized N comments` footer mirroring the post-capture note.
- Copy button copies post summary + comments summary as plain text.

The card lives in a `TYPE_APPLICATION_OVERLAY` window with a tap-outside-to-dismiss scrim.

## Reddit JSON client (`reddit/RedditJsonClient.kt`)

Anonymous use of `https://www.reddit.com/...json`. **Required header:** `User-Agent: RedditTLDR/1.0 (Android; by /u/stanley)`. No OAuth. Two endpoints used:

- `fetchComments(postId, limit)` → `comments/{id}.json`
- `searchPostId(subreddit, title)` → `r/{sub}/search.json?restrict_sr=on&q=...` — title-search fallback when we couldn't extract a direct ID.

## Build & sideload

JBR (Android Studio's bundled JDK) is required for Gradle:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/RedditTLDR-debug.apk
```

The `~/Downloads/RedditTLDR-debug.apk` location is the established sideload convention.

## Debug logging

`util/DebugLog.kt` keeps a per-session in-memory ring + flushes to `filesDir/reddittldr_debug.log`. The settings screen has a "Debug logs" action that surfaces it. When extraction misbehaves, the log shows `redditRoots=N childCounts=…`, `postId=…`, `forwardScrolls=N`, `hitComments=true/false`, `result=…`. **If you change the extractor, keep these `logKv` calls in place** — without them, diagnosing extraction failures means flying blind.

## Things that have been tried and removed — don't bring them back

- **`comments/{id}.json` as the primary extractor.** Used it as strategy #2 for ages; ID detection was unreliable enough that it failed on most real posts. Now used only to fetch the comment list once the user opts in.
- **Compose semantic-node walking by resource ID.** Reddit obfuscates IDs per release. Content-based heuristics (text length, scrollable parent, viewport bounds) survive version bumps; ID lookups don't.
- **Sharing into the app via share-sheet.** Explicit non-goal — the whole point is one tap with no app switching.
- **Reading from a single Reddit window root.** See the multi-window rule above.
