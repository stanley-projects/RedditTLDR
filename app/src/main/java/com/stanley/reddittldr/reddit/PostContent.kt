package com.stanley.reddittldr.reddit

data class PostContent(
    val title: String?,
    /**
     * The full captured screen content — post body and (usually) the comment
     * section that follows. We deliberately don't split the two at extraction
     * time: Reddit's comment-section header text varies between app versions
     * and a missed split silently corrupts the body summary or hides the
     * comments button. Instead we send this whole blob to Claude for both
     * summarize calls, and the prompts tell the model which part to focus on.
     */
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
