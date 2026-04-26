package com.stanley.reddittldr.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.stanley.reddittldr.reddit.ExtractionMethod
import com.stanley.reddittldr.reddit.PostContent
import com.stanley.reddittldr.reddit.PostExtractor
import java.util.concurrent.atomic.AtomicReference

class RedditWatcherService : AccessibilityService() {

    private val extractor by lazy { PostExtractor(this) }

    /** True while the user has explicitly drag-dismissed the bubble.
     *  Stays true through intra-Reddit navigation events; cleared only when
     *  the user genuinely leaves Reddit and comes back (or restarts us). */
    @Volatile private var userDismissed: Boolean = false

    /** Did the previous accessibility event originate from Reddit? Used to detect
     *  the boundary "non-Reddit -> Reddit" so we can reset [userDismissed]. */
    @Volatile private var lastWasReddit: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef.set(this)
        // If Reddit is already foreground when the user enables us, spin up the bubble
        // immediately — we won't get a fresh TYPE_WINDOW_STATE_CHANGED for it.
        if (isRedditForeground()) {
            lastWasReddit = true
            userDismissed = false
            startBubble()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Our own overlay windows (bubble, summary card, dismiss target) emit
        // window-state events too. Ignore them — they're noise, not real app
        // switches, and reacting to them was tearing down the summary overlay
        // milliseconds after it was shown.
        if (pkg == packageName) return

        // Security: we only READ Reddit content. For other packages we only observe
        // the package name to toggle the bubble off.
        if (isRedditPackage(pkg)) {
            // Reset the dismiss flag only when crossing the boundary from another app
            // back into Reddit — not on every intra-Reddit navigation event.
            if (!lastWasReddit) {
                userDismissed = false
            }
            lastWasReddit = true
            if (!userDismissed) {
                startBubble()
            }
        } else {
            // Don't let transient popups (IME, permission dialogs) kill the bubble —
            // only stop if Reddit is genuinely no longer in the window stack.
            if (!isRedditForeground()) {
                stopBubble()
                lastWasReddit = false
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instanceRef.compareAndSet(this, null)
        stopBubble()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instanceRef.compareAndSet(this, null)
        stopBubble()
        super.onDestroy()
    }

    private fun isRedditForeground(): Boolean {
        return try {
            windows?.any { w ->
                val p = w.root?.packageName?.toString() ?: return@any false
                isRedditPackage(p)
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isRedditPackage(pkg: String): Boolean {
        return pkg == REDDIT_PACKAGE || pkg.startsWith("com.reddit.")
    }

    private fun startBubble() {
        // startForegroundService is idempotent — if BubbleService is already alive it
        // simply re-fires onStartCommand, which we handle. We deliberately do NOT
        // gate this with a local "running" flag, because the service can be killed
        // by the system without us being notified, leaving us stuck.
        val intent = Intent(this, BubbleService::class.java).apply {
            action = BubbleService.ACTION_START
        }
        try {
            startForegroundService(intent)
        } catch (_: Exception) {
        }
    }

    private fun stopBubble() {
        val intent = Intent(this, BubbleService::class.java)
        try {
            stopService(intent)
        } catch (_: Exception) {
        }
    }

    /** Called from BubbleService when the user drag-dismisses the bubble.
     *  The bubble stays gone until the user leaves Reddit and returns. */
    fun markUserDismissed() {
        userDismissed = true
    }

    suspend fun extractCurrentPost(): PostContent {
        // Double-check we're still in Reddit before reading any content.
        if (!isRedditForeground()) {
            return PostContent(
                title = null,
                body = "",
                sourceUrl = null,
                postId = null,
                subreddit = null,
                extractionMethod = ExtractionMethod.FAILED,
                isPartial = false
            )
        }
        return extractor.extract()
    }

    companion object {
        const val REDDIT_PACKAGE = "com.reddit.frontpage"

        private val instanceRef = AtomicReference<RedditWatcherService?>(null)

        val instance: RedditWatcherService?
            get() = instanceRef.get()
    }
}
