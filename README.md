# RedditTLDR

**A floating bubble that summarizes any Reddit post with a single tap — powered by Claude AI. No root required. Works inside the official Reddit app.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Claude API](https://img.shields.io/badge/Powered%20by-Claude%20API-D97706)](https://console.anthropic.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-6B7280)](LICENSE)

---

## What is RedditTLDR?

RedditTLDR is an Android app that draws a small draggable bubble over the Reddit app. Tap it while reading any post — the app captures the post text, sends it to the [Anthropic Claude API](https://docs.anthropic.com), and shows a concise summary in an overlay card without ever leaving Reddit.

**Problem it solves:** Reddit posts are often long. You want to know if it's worth reading before committing. RedditTLDR gives you a 1–7 bullet summary in about 3 seconds, directly on top of whatever you're reading.

---

## Features

- **One-tap post summary** — tap the bubble, get a summary. No app switching.
- **Comment summarization** — optional second tap summarizes the captured comment thread
- **Three summary lengths** — Short (1–2 sentences), Medium (TL;DR + bullets), Detailed (full context)
- **Three Claude models** — Haiku (fastest/cheapest), Sonnet (balanced), Opus (highest quality)
- **Draggable bubble** — docks to either screen edge; drag to the dismiss target to hide
- **Partial-content warning** — banner shown when a very long post hits the scroll cap
- **Copy button** — copies post + comments summary as plain text
- **Your key, your data** — API key stored encrypted on-device; nothing is sent anywhere except your own Anthropic API endpoint

---

## Requirements

- Android 8.0+ (API 26+)
- The official [Reddit app](https://play.google.com/store/apps/details?id=com.reddit.frontpage) (`com.reddit.frontpage`)
- An [Anthropic API key](https://console.anthropic.com) — Haiku is the default model and costs fractions of a cent per summary

---

## Installation

### Option A — Sideload the APK (easiest)

1. Download `RedditTLDR-debug.apk` from the [Releases](../../releases) page
2. Enable **Install from unknown sources** in Android Settings → Apps → Special app access
3. Open the APK on your device and install
4. Follow the in-app setup wizard

### Option B — Build from source

See [Building from source](#building-from-source) below.

---

## Setup (3 steps)

1. **Grant permissions** — open RedditTLDR, tap each permission row:
   - *Accessibility service* — required so the app can read the post text
   - *Display over other apps* — required to show the floating bubble
   - *Notifications* — required to keep the foreground service alive

2. **Add your Claude API key** — paste your key from [console.anthropic.com](https://console.anthropic.com). It is stored encrypted and never leaves your device.

3. **Open Reddit** — the bubble appears automatically. Tap it to summarize.

> **Note:** After installing or updating the app, you may need to toggle the Accessibility service **off and on** in Settings → Accessibility → RedditTLDR for new capabilities to take effect.

---

## How it works

```
Reddit app is open
       │
       │  AccessibilityService watches window events
       ▼
 [Floating bubble]  ←── you tap here
       │
       │  Bubble becomes non-touchable so gestures pass through to Reddit
       ▼
 PostExtractor
   • Scrolls up to the top of the post (max 5 swipes)
   • Swipes down through the post + comments (max 6 swipes)
   • Reads the accessibility tree after each swipe to capture visible text
   • Uses fingerprint hashing to detect when the page actually moved
       │
       ▼
 ClaudeRepository
   • Sends captured text to Anthropic API with your chosen model + length
   • Post-summary prompt: "summarize only what the OP wrote, ignore comments"
   • Comments-summary prompt: "summarize the discussion in the comments"
       │
       ▼
 SummaryOverlay
   • Styled card overlaid on top of Reddit
   • "Summarize comments" button uses already-captured text — no second API call
   • Copy button exports everything as plain text
```

### Why AccessibilityService and not the Reddit API?

Reddit's official API has restrictive rate limits and requires OAuth. The Accessibility approach reads whatever is on-screen, works anonymously, requires no Reddit account, and survives Reddit API changes. The tradeoff is that the app only works with the official Reddit app (`com.reddit.frontpage`), not third-party clients.

---

## Building from source

**Requires:** Android Studio with JBR (the bundled JDK). Clone the repo, then:

```bash
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleDebug
```

On Windows (Git Bash / WSL):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Architecture

```
app/src/main/java/com/stanley/reddittldr/
├── MainActivity.kt                    # Compose settings screen (API key, model, length)
├── api/
│   ├── ClaudeRepository.kt            # Anthropic API — summarize() + summarizeComments()
│   └── models/                        # Request / response data classes
├── data/
│   ├── ClaudeModel.kt                 # Model enum (Haiku / Sonnet / Opus)
│   ├── SettingsRepository.kt          # EncryptedSharedPreferences wrapper
│   └── SummaryLength.kt
├── reddit/
│   ├── PostContent.kt                 # Extraction result data class
│   ├── PostExtractor.kt               # The scroll-and-capture engine
│   └── RedditJsonClient.kt            # Unused in main path; kept for post-ID lookup
├── service/
│   ├── BubbleService.kt               # Foreground service — bubble lifecycle
│   └── RedditWatcherService.kt        # AccessibilityService — start/stop BubbleService
├── ui/
│   ├── OnboardingSection.kt           # Permission setup UI (Compose)
│   ├── SettingsScreen.kt              # Full settings screen (Compose)
│   └── overlay/
│       ├── BubbleView.kt              # Draggable circle
│       ├── DismissScrimView.kt        # Bottom gradient on drag
│       ├── DismissTargetView.kt       # Drag-to-dismiss X target
│       └── SummaryOverlay.kt          # Summary card (classic Views — Compose
│                                      # doesn't work with TYPE_APPLICATION_OVERLAY)
└── util/
    ├── DebugLog.kt                    # Per-session in-memory log + file flush
    └── PermissionState.kt
```

**Key design decisions:**

| Decision | Why |
|---|---|
| Gesture-based scrolling (`dispatchGesture`) instead of `ACTION_SCROLL_DOWN` | `ACTION_SCROLL_DOWN` targets the largest-by-area scrollable, which on Reddit's post screen is the comments LazyColumn, not the post body. Gestures hit whatever is under the touch point. |
| Bubble made non-touchable during extraction | Dispatched gestures were absorbed by the draggable bubble window sitting above Reddit. `FLAG_NOT_TOUCHABLE` lets them pass through. |
| No body/comment split at extraction time | Reddit's comment-section header text varies by app version. Splitting by marker is fragile. Instead, Claude's system prompt tells the model what to focus on. |
| Classic Views for all overlay UI | Compose composables do not render correctly inside `TYPE_APPLICATION_OVERLAY` windows. |
| `canPerformGestures="true"` in accessibility config | Without this declaration the system silently drops dispatched gestures — no error, callback just never fires. |

---

## Privacy & Security

| Protection | Implementation |
|---|---|
| API key storage | `EncryptedSharedPreferences` with AES-256-GCM master key |
| Screenshot protection | `FLAG_SECURE` on settings activity — key never appears in recents or screenshots |
| Backup exclusion | `allowBackup=false`; `data_extraction_rules.xml` explicitly excludes the prefs file from cloud backup and device transfer |
| Network security | `network_security_config.xml` — HTTPS only, system CAs only in release builds (no user-installed CA can MITM the API key) |
| Data retention | Nothing persisted beyond the summary card's lifetime. No analytics, no crash reporting, no telemetry. |

---

## Limitations

- **Android only** — this is an AccessibilityService + overlay app; no iOS equivalent is possible
- **Official Reddit app only** — tested against `com.reddit.frontpage`. Third-party Reddit clients are not supported
- **Long posts may be partial** — very long posts (more than ~6 screenfuls) hit the scroll cap; the card shows a "partial content" warning
- **Scroll timing is conservative** — the app waits 500ms after each swipe for Reddit's accessibility tree to update. This keeps capture reliable at the cost of speed

---

## Contributing

Issues and PRs welcome. See [CLAUDE.md](CLAUDE.md) for architecture details, hard-won extraction rules, and the agent memory log (this repo is co-developed with AI agents — Codex and Claude — using a shared agent log convention).

---

## License

MIT — see [LICENSE](LICENSE).

---

*Built with [Anthropic Claude](https://anthropic.com) · Inspired by the universal need to not read a 47-paragraph Reddit post before knowing if it's worth it*
