package com.stanley.reddittldr.reddit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.stanley.reddittldr.util.DebugLog
import kotlin.coroutines.resume
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read the currently visible Reddit post off the screen.
 *
 * The flow on every bubble tap:
 *
 *   1. Walk to the top of the post.   Up to MAX_UP_SCROLLS swipes — stops
 *      when the page won't move further (we've reached the top) or the cap
 *      is hit (safety net for an accidental tap on a feed where there is no
 *      "top of post"). No content is captured during this phase, only
 *      positioning.
 *
 *   2. Read forward, capturing each visible chunk.   Up to MAX_DOWN_SCROLLS
 *      swipes — stops when the page won't move further or the cap is hit.
 *      Everything visible is captured: the post body AND the comment section
 *      that follows it. We do NOT stop at the comment-section boundary; the
 *      whole pass is meant to grab comments too so the comment summarizer
 *      doesn't need a separate fetch.
 *
 *   3. Split the captured set into body + comments at the first comment
 *      marker ("Sort by:", "Be the first to comment", etc.).
 *
 * No web requests, no fallbacks. The accessibility tree is the source of
 * truth for both the body and the comments.
 */
class PostExtractor(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class Direction { UP, DOWN }

    suspend fun extract(): PostContent = coroutineScope {
        val roots = redditRoots()
        if (roots.isEmpty()) {
            DebugLog.log("extract", "no Reddit windows found - aborting")
            return@coroutineScope failed()
        }
        DebugLog.logKv(
            "extract",
            "redditRoots" to roots.size,
            "childCounts" to roots.joinToString(",") { it.childCount.toString() }
        )

        val postId = findPostIdAcross(roots)
        DebugLog.logKv("extract", "postId" to (postId ?: "<none>"))

        readFromScreen(postId)
    }

    /**
     * All Reddit-app accessibility windows. Reddit's modern UI is laid out
     * across several sibling windows; reading just one gives mostly chrome.
     */
    private fun redditRoots(): List<AccessibilityNodeInfo> = try {
        service.windows
            ?.mapNotNull { it.root }
            ?.filter {
                val p = it.packageName?.toString() ?: return@filter false
                p == REDDIT_PACKAGE || p.startsWith("com.reddit.")
            }
            ?: emptyList()
    } catch (e: Exception) {
        DebugLog.logKv("redditRoots", "exception" to (e.message ?: e.javaClass.simpleName))
        emptyList()
    }

    private suspend fun readFromScreen(postId: String?): PostContent = coroutineScope {
        // Phase 1: walk to the top of the post. Capped — if the user
        // accidentally tapped on a feed (no post top to find), we don't
        // scroll the feed forever.
        var upScrolls = 0
        while (isActive && upScrolls < MAX_UP_SCROLLS) {
            if (!scrollOneStep(Direction.UP)) break
            upScrolls++
        }
        DebugLog.logKv("read", "phase" to "afterUp", "upScrolls" to upScrolls)

        // Phase 2: read forward, capturing every visible chunk including the
        // comments below the body. Capped — for very long posts or comment
        // threads we stop after MAX_DOWN_SCROLLS swipes. linkedSetOf gives us
        // de-duplication while preserving the order lines first appear, which
        // (because we always read from the top down) matches visual order.
        val ordered = linkedSetOf<String>()
        var headingTitle: String? = null

        fun mergeOnce() {
            val visible = collectVisibleTextAcross(redditRoots())
            for (vt in visible) {
                if (headingTitle == null && vt.isHeading &&
                    vt.text.length in 10..300 && !looksLikeChrome(vt.text)
                ) {
                    headingTitle = vt.text
                }
                ordered.add(vt.text)
            }
        }

        mergeOnce()
        DebugLog.logKv("read", "phase" to "initialAtTop", "lines" to ordered.size)

        var downScrolls = 0
        while (isActive && downScrolls < MAX_DOWN_SCROLLS) {
            if (!scrollOneStep(Direction.DOWN)) break
            downScrolls++
            mergeOnce()
        }
        DebugLog.logKv(
            "read",
            "phase" to "afterDown",
            "downScrolls" to downScrolls,
            "lines" to ordered.size
        )

        // Phase 3: build a single captured-content blob. We do NOT split body
        // from comments at extraction time — Reddit's comment-section header
        // text varies between app versions and a missed split silently breaks
        // the post summary (mixing comments in) or hides the comments button.
        // Claude does the differentiation via prompt instructions when each
        // summary is requested.
        val orderedList = ordered.toList()
        val title = headingTitle ?: pickTitleFromOrdered(orderedList)
        val subreddit = orderedList
            .asSequence()
            .mapNotNull { SUBREDDIT_PATTERN.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()

        val body = orderedList
            .asSequence()
            .filter { it != title }
            .filterNot { looksLikeChrome(it) }
            .filterNot { it.startsWith("r/") || it.startsWith("u/") }
            .filter { it.length >= MIN_LINE_LEN }
            .distinct()
            .joinToString("\n\n")

        if (body.length < MIN_BODY_LEN) {
            DebugLog.logKv(
                "read",
                "result" to "FAIL",
                "bodyLen" to body.length,
                "title" to (title?.take(60) ?: "<none>"),
                "lines" to ordered.size,
                "bodySnippet" to "\"${DebugLog.snippet(body, 240)}\""
            )
            return@coroutineScope failed()
        }

        DebugLog.logKv(
            "read",
            "result" to "SUCCESS",
            "title" to (title?.take(60) ?: "<none>"),
            "subreddit" to (subreddit ?: "<none>"),
            "bodyLen" to body.length,
            "snippet" to "\"${DebugLog.snippet(body, 400)}\""
        )

        PostContent(
            title = title,
            body = body,
            sourceUrl = null,
            postId = postId,
            subreddit = subreddit,
            extractionMethod = if (downScrolls == 0 && upScrolls == 0) {
                ExtractionMethod.SCREEN
            } else {
                ExtractionMethod.SCREEN_SCROLLED
            },
            // We hit the down-scroll cap, so we may not have reached the end
            // of the page. Surfaced as a "partial content" banner on the card.
            isPartial = downScrolls >= MAX_DOWN_SCROLLS,
            linesCaptured = ordered.size,
            forwardScrolls = downScrolls
        )
    }

    /**
     * The single scroll primitive. Dispatches a controlled-distance swipe and
     * verifies the page actually moved by comparing visible-text fingerprints
     * before and after. Returns true only if the fingerprint changed.
     */
    private suspend fun scrollOneStep(direction: Direction): Boolean {
        val before = visibleFingerprint(redditRoots())
        val dispatched = dispatchSwipe(direction)
        if (!dispatched) {
            DebugLog.logKv("scroll", "direction" to direction.name, "dispatched" to false)
            return false
        }
        delay(SCROLL_WAIT_MS)
        var after = visibleFingerprint(redditRoots())
        if (after == before) {
            // Lazy-list hydration sometimes lags; give it one more beat.
            delay(EXTRA_SCROLL_SETTLE_MS)
            after = visibleFingerprint(redditRoots())
        }
        val moved = after != before
        DebugLog.logKv("scroll", "direction" to direction.name, "moved" to moved)
        return moved
    }

    /**
     * Walk every supplied root and collect text nodes inside the viewport,
     * sorted top-to-bottom across the merged set. Filtering by viewport drops
     * stale lazy-list nodes that have scrolled off-screen.
     */
    private fun collectVisibleTextAcross(roots: List<AccessibilityNodeInfo>): List<VisibleText> {
        val metrics = service.resources.displayMetrics
        val viewport = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val out = mutableListOf<VisibleText>()
        val r = Rect()
        for (root in roots) {
            walk(root) { node ->
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                val combined = if (t.length >= d.length) t else d
                if (combined.isBlank()) return@walk
                node.getBoundsInScreen(r)
                if (r.isEmpty || !Rect.intersects(r, viewport)) return@walk
                out += VisibleText(text = combined, top = r.top, isHeading = node.isHeading)
            }
        }
        out.sortBy { it.top }
        val seen = HashSet<String>()
        return out.filter { seen.add(it.text) }
    }

    /**
     * Cheap stable hash of the currently visible text set. The scroll primitive
     * uses this to confirm the page actually moved between attempts.
     */
    private fun visibleFingerprint(roots: List<AccessibilityNodeInfo>): Int {
        val visible = collectVisibleTextAcross(roots)
        var h = 1
        for (vt in visible) h = 31 * h + vt.text.hashCode()
        return h
    }

    private fun pickTitleFromOrdered(ordered: List<String>): String? {
        val subAt = ordered.indexOfFirst { it.startsWith("r/") }
        val after = if (subAt >= 0) ordered.drop(subAt + 1) else ordered
        return after.firstOrNull {
            it.length in 15..300 &&
                !looksLikeChrome(it) &&
                !it.startsWith("u/") &&
                !it.startsWith("r/")
        }
    }

    /** Best-effort scan for a Reddit post ID across all Reddit windows. */
    private fun findPostIdAcross(roots: List<AccessibilityNodeInfo>): String? {
        val sb = StringBuilder()
        for (root in roots) {
            walk(root) { node ->
                node.text?.let { sb.append(it).append(' ') }
                node.contentDescription?.let { sb.append(it).append(' ') }
                node.viewIdResourceName?.let { sb.append(it).append(' ') }
            }
        }
        val haystack = sb.toString()
        for (pattern in POST_ID_PATTERNS) {
            pattern.find(haystack)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * Dispatch a vertical swipe gesture in the screen-center column. Returns
     * true only when the system reports the gesture as completed. Capped at
     * GESTURE_TIMEOUT_MS so a misconfigured `canPerformGestures` or a
     * transient state where the system drops the gesture can't hang the
     * whole extraction.
     */
    private suspend fun dispatchSwipe(direction: Direction): Boolean {
        val metrics = service.resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val h = metrics.heightPixels.toFloat()
        // ~50% of viewport per step. Finger drags UP (yStart > yEnd) to scroll
        // the page DOWN, revealing content below the current view.
        val (yStart, yEnd) = if (direction == Direction.DOWN) {
            h * 0.78f to h * 0.28f
        } else {
            h * 0.28f to h * 0.78f
        }
        val path = Path().apply {
            moveTo(cx, yStart)
            lineTo(cx, yEnd)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (!cont.isCompleted) cont.resume(true)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        if (!cont.isCompleted) cont.resume(false)
                    }
                }
                val dispatched = try {
                    service.dispatchGesture(gesture, callback, mainHandler)
                } catch (e: Exception) {
                    DebugLog.logKv("swipe", "exception" to (e.message ?: e.javaClass.simpleName))
                    false
                }
                if (!dispatched && !cont.isCompleted) cont.resume(false)
            }
        }
        if (result == null) {
            DebugLog.log("swipe", "timeout - callback never fired")
            return false
        }
        return result
    }

    /** Chrome detection - only flags short lines. */
    private fun looksLikeChrome(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 6) return true
        if (VOTE_COUNT_REGEX.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        if (RELATIVE_TIME_REGEX.matches(lower)) return true
        if (trimmed.length <= 40 && CHROME_KEYWORD_REGEX.containsMatchIn(lower)) return true
        return false
    }

    private fun walk(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        visitor(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visitor)
        }
    }

    private fun failed() = PostContent(
        title = null,
        body = "",
        sourceUrl = null,
        postId = null,
        subreddit = null,
        extractionMethod = ExtractionMethod.FAILED,
        isPartial = false
    )

    private data class VisibleText(
        val text: String,
        val top: Int,
        val isHeading: Boolean
    )

    companion object {
        private const val REDDIT_PACKAGE = "com.reddit.frontpage"

        private const val MIN_BODY_LEN = 200
        private const val MIN_LINE_LEN = 8

        // Hard caps chosen by spec: stop runaway scrolling on accidental taps
        // (e.g. on the feed, where there is no "top of post" / "end of post").
        // Most real posts should hit a natural stop (page can't move) well
        // before either limit.
        private const val MAX_UP_SCROLLS = 5
        private const val MAX_DOWN_SCROLLS = 6

        private const val SCROLL_WAIT_MS = 500L
        private const val EXTRA_SCROLL_SETTLE_MS = 500L
        private const val GESTURE_DURATION_MS = 500L
        private const val GESTURE_TIMEOUT_MS = 3000L

        private val SUBREDDIT_PATTERN = Regex("""\br/([A-Za-z0-9_]{3,21})\b""")

        private val POST_ID_PATTERNS = listOf(
            Regex("""reddit\.com/r/[^/\s]+/comments/([a-z0-9]{4,13})"""),
            Regex("""/comments/([a-z0-9]{4,13})\b"""),
            Regex("""\bt3_([a-z0-9]{4,13})\b"""),
            Regex("""redd\.it/([a-z0-9]{4,13})\b""")
        )

        private val CHROME_KEYWORD_REGEX = Regex(
            """\b(upvote|downvote|share|save|reply|comment|comments|posted by|joined|""" +
                """members online|subscribe|subscribed|award|crosspost|hide|report|""" +
                """follow|following|more options|back button|join the conversation|""" +
                """add a comment|sort by|view all comments)\b"""
        )
        private val RELATIVE_TIME_REGEX = Regex("""^\d+\s?(s|sec|m|min|h|hr|d|w|mo|y)$""")
        private val VOTE_COUNT_REGEX = Regex("""^[\d.]+[kKmM]?$""")
    }
}
