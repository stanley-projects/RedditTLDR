package com.stanley.reddittldr.reddit

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.stanley.reddittldr.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope

/**
 * Read the currently visible Reddit post off the screen.
 *
 * Single strategy: walk the Reddit app's accessibility windows (Reddit splits
 * its UI across multiple sibling windows — toolbar overlay, body container,
 * comment sheet, etc.), collect every text node whose bounds are inside the
 * viewport, scroll down a few times if we haven't reached the comments
 * section yet, scroll back up, return.
 *
 * No web requests, no fallbacks. The accessibility tree is the source of truth.
 */
class PostExtractor(private val service: AccessibilityService) {

    suspend fun extract(): PostContent = coroutineScope {
        val roots = redditRoots()
        if (roots.isEmpty()) {
            DebugLog.log("extract", "no Reddit windows found — aborting")
            return@coroutineScope failed()
        }
        DebugLog.logKv(
            "extract",
            "redditRoots" to roots.size,
            "childCounts" to roots.joinToString(",") { it.childCount.toString() }
        )

        val postId = findPostIdAcross(roots)
        // NOTE: subreddit is detected during the screen read, where we have
        // top-to-bottom order — the first "r/<sub>" line is the post's own
        // subreddit. A flat regex over the whole tree picked up sidebar promos.
        DebugLog.logKv("extract", "postId" to (postId ?: "<none>"))

        readFromScreen(postId)
    }

    /**
     * All Reddit-app accessibility windows. Reddit's modern UI is laid out
     * across several sibling windows; reading just one (e.g. the toolbar
     * overlay) gives 4 lines of chrome. We walk the whole set as a single
     * logical tree.
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

    /** Read everything visible across all Reddit windows; scroll down to capture more
     *  if the comments boundary is still off-screen; scroll back; assemble title + body. */
    private suspend fun readFromScreen(postId: String?): PostContent = coroutineScope {
        val ordered = linkedSetOf<String>()
        var hitComments = false
        var headingTitle: String? = null

        fun mergeOnce() {
            val visible = collectVisibleTextAcross(redditRoots())
            for (vt in visible) {
                // Only honor comment markers AFTER we've captured at least some real body text —
                // Reddit's toolbar shows "Comments" on every screen, and tripping on it before
                // any body is collected throws away the post entirely.
                if (ordered.size >= MIN_LINES_BEFORE_COMMENT_STOP && looksLikeCommentMarker(vt.text)) {
                    hitComments = true
                    return
                }
                if (headingTitle == null && vt.isHeading &&
                    vt.text.length in 10..300 && !looksLikeChrome(vt.text)
                ) {
                    headingTitle = vt.text
                }
                ordered.add(vt.text)
            }
        }

        mergeOnce()
        DebugLog.logKv("read", "initialLines" to ordered.size, "hitComments" to hitComments)

        val scrollDownId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        val scrollUpId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id

        var forwardCount = 0
        while (isActive && forwardCount < MAX_SCROLL_ITERATIONS && !hitComments) {
            val scrollable = findScrollableAcross(redditRoots()) ?: break
            val sizeBefore = ordered.size
            val ok = scrollable.performAction(scrollDownId, null)
            if (!ok) break
            forwardCount++
            delay(SCROLL_WAIT_MS)
            mergeOnce()
            // No new lines AND no comments boundary → we've hit the bottom.
            if (ordered.size == sizeBefore && !hitComments) break
        }
        DebugLog.logKv(
            "read",
            "forwardScrolls" to forwardCount,
            "totalLines" to ordered.size,
            "hitComments" to hitComments
        )

        // Restore the user's scroll position.
        repeat(forwardCount) {
            val back = findScrollableAcross(redditRoots()) ?: return@repeat
            back.performAction(scrollUpId, null)
            delay(SCROLL_WAIT_MS)
        }

        val title = headingTitle ?: pickTitleFromOrdered(ordered)
        // Subreddit = the FIRST "r/<sub>" we saw in top-to-bottom order. Reddit's
        // post screen puts the subreddit header right above the title. A flat
        // regex over the whole tree picks up sidebar promos ("r/unsloth" on a
        // r/DigitalIncomePath post) — the ordering filter prevents that.
        val subreddit = ordered
            .asSequence()
            .mapNotNull { SUBREDDIT_PATTERN.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()

        val body = ordered
            .asSequence()
            .filter { it != title }
            .filterNot { looksLikeChrome(it) }
            .filterNot { it.startsWith("r/") || it.startsWith("u/") }
            .filter { it.length >= MIN_LINE_LEN }
            .toList()
            .distinct()
            .joinToString("\n\n")

        if (body.length < MIN_BODY_LEN) {
            DebugLog.logKv(
                "read",
                "result" to "FAIL",
                "bodyLen" to body.length,
                "title" to (title?.take(60) ?: "<none>"),
                "lines" to ordered.size,
                "linesSnippet" to "\"${DebugLog.snippet(ordered.joinToString(" | "), 240)}\""
            )
            return@coroutineScope failed()
        }

        val isPartial = !hitComments && forwardCount >= MAX_SCROLL_ITERATIONS
        DebugLog.logKv(
            "read",
            "result" to "SUCCESS",
            "title" to (title?.take(60) ?: "<none>"),
            "subreddit" to (subreddit ?: "<none>"),
            "bodyLen" to body.length,
            "isPartial" to isPartial,
            "snippet" to "\"${DebugLog.snippet(body)}\""
        )

        PostContent(
            title = title,
            body = body,
            sourceUrl = null,
            postId = postId,
            subreddit = subreddit,
            extractionMethod = if (forwardCount == 0) ExtractionMethod.SCREEN else ExtractionMethod.SCREEN_SCROLLED,
            isPartial = isPartial,
            linesCaptured = ordered.size,
            forwardScrolls = forwardCount
        )
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
        // De-dup identical text appearing in multiple Reddit windows at similar positions.
        val seen = HashSet<String>()
        return out.filter { seen.add(it.text) }
    }

    private fun pickTitleFromOrdered(ordered: Set<String>): String? {
        val list = ordered.toList()
        val subAt = list.indexOfFirst { it.startsWith("r/") }
        val after = if (subAt >= 0) list.drop(subAt + 1) else list
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
     * Pick the largest scrollable that explicitly advertises ACTION_SCROLL_DOWN
     * across all Reddit windows. Filtering on SCROLL_DOWN keeps us vertical and
     * ignores Reddit's swipe-between-posts horizontal pager.
     */
    private fun findScrollableAcross(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val scrollDownId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val rect = Rect()
        for (root in roots) {
            walk(root) { node ->
                if (!node.isScrollable) return@walk
                if (node.actionList.none { it.id == scrollDownId }) return@walk
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
        }
        return best
    }

    private fun looksLikeCommentMarker(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return false
        return COMMENT_MARKERS.any { lower == it || lower.startsWith(it) }
    }

    /** Chrome detection — only flags SHORT lines. Substring matches inside long prose
     *  ("subscription" containing "subscribe") were eating real titles. */
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
        private const val MAX_SCROLL_ITERATIONS = 12
        private const val SCROLL_WAIT_MS = 300L

        /** Don't honour a comments boundary until we've seen this many lines —
         *  the toolbar shows "Comments" everywhere, and we'd otherwise stop before
         *  the post body has even rendered. */
        private const val MIN_LINES_BEFORE_COMMENT_STOP = 8

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

        /** Lines that ONLY appear at the actual comments-section boundary.
         *
         *  Excluded on purpose:
         *  - "comments" — toolbar button, on every screen
         *  - "join the conversation" — sticky comment-input bar, always visible
         *  - "add a comment" — same sticky bar variant
         *  - "view all comments" — feed-card link, not a post-screen boundary
         *
         *  These were causing the extractor to declare "we hit comments" on the very
         *  first read, before the post body had even rendered, so it never scrolled. */
        private val COMMENT_MARKERS = listOf(
            "sort by:",
            "sort comments",
            "be the first to comment",
            "no comments yet"
        )
    }
}
