package com.stanley.reddittldr.reddit

data class PostContent(
    val title: String?,
    val body: String,
    val sourceUrl: String?,
    val postId: String?,
    val subreddit: String?,
    val extractionMethod: ExtractionMethod,
    val isPartial: Boolean,
    /** Total unique text lines folded into the body. */
    val linesCaptured: Int = 0,
    /** Number of programmatic down-scrolls performed during capture. */
    val forwardScrolls: Int = 0
)

enum class ExtractionMethod {
    /** Read straight from the visible accessibility tree, no scrolling needed. */
    SCREEN,

    /** Read from the screen, with one or more programmatic vertical scrolls to capture
     *  content below the fold. */
    SCREEN_SCROLLED,

    /** Could not read the post — Reddit window not found, or visible text too short. */
    FAILED
}
