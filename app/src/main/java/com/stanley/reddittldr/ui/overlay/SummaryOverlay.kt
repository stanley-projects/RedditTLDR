package com.stanley.reddittldr.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stanley.reddittldr.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Summary overlay card. Built programmatically because Compose cooperates
 * poorly with TYPE_APPLICATION_OVERLAY windows.
 *
 * Visual system applies the impeccable design principles:
 *  - tinted-neutral surface (no pure black/gray) with a hairline border
 *  - asymmetric vertical padding (more breathing room at the top)
 *  - serif for prose (body / comments) so reading rhythm differs from UI
 *  - sans (system) for labels, meta, and buttons; small caps + tracked
 *    eyebrows mark the top of each section
 *  - hairline rule between content and the action footer
 *  - weighty Sonner-style primary; outlined pill secondary
 *  - single deliberate entrance (340 ms blur-free fade + lift, cubic-bezier
 *    ease-out), no perpetual motion, prefers-reduced-motion friendly
 *
 * Behaviors preserved exactly: tap-outside-to-dismiss, Copy, X-icon close,
 * partial-content banner, optional Summarize-comments button, comments
 * appended in the same scroll view, captureNote line, the loading state on
 * the primary button, error toast on failure.
 */
data class CommentsSummaryResult(
    val summary: String,
    val count: Int,
    val sourcePostTitle: String?
)

class SummaryOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val coroutineScope: CoroutineScope,
    private val summaryText: String,
    private val isPartial: Boolean,
    private val captureNote: String?,
    private val onSummarizeComments: (suspend () -> Result<CommentsSummaryResult>)?,
    private val onDismiss: () -> Unit
) {

    private var root: View? = null
    private var commentsSummary: String? = null

    fun attach() {
        if (root != null) return

        // ──────────────── scrim + tap-to-dismiss container ────────────────
        val container = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.scrim))
            setOnClickListener { dismiss() }
        }

        // ──────────────── card surface ────────────────
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.summary_card_bg)
            // Asymmetric: more breathing room at the top, slightly tighter at
            // the bottom where the actions are.
            setPadding(dp(22), dp(24), dp(22), dp(16))
            // Swallow taps so they don't fall through to the dismiss scrim.
            setOnClickListener { /* swallow */ }
        }

        // ──────────────── header: eyebrow + close ────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val eyebrowRow = makeEyebrowRow("Summary").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(eyebrowRow)

        val closeIcon = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            background = null
            setColorFilter(context.getColor(R.color.text_tertiary))
            setOnClickListener { dismiss() }
            val s = dp(28)
            layoutParams = LinearLayout.LayoutParams(s, s)
            // Tighter padding so the icon doesn't visually overpower the eyebrow.
            val ip = dp(4)
            setPadding(ip, ip, ip, ip)
        }
        header.addView(closeIcon)
        card.addView(header)

        // ──────────────── partial-capture banner ────────────────
        if (isPartial) {
            val banner = TextView(context).apply {
                text = context.getString(R.string.summary_partial_banner)
                setTextColor(context.getColor(R.color.partial_amber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setLetterSpacing(0.10f)
                isAllCaps = true
                typeface = Typeface.SANS_SERIF
                background = context.getDrawable(R.drawable.partial_banner_bg)
                val padH = dp(10); val padV = dp(6)
                setPadding(padH, padV, padH, padV)
            }
            val bannerLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            card.addView(banner, bannerLp)
        }

        // ──────────────── scrollable content ────────────────
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(0, pad, 0, pad)
        }

        val body = TextView(context).apply {
            text = summaryText
            setTextColor(context.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setLineSpacing(dp(5).toFloat(), 1f)
            // Slight on-screen tracking nudge for serif body — improves
            // legibility at this size on AMOLED displays.
            setLetterSpacing(0.005f)
            typeface = Typeface.SERIF
            setTextIsSelectable(true)
        }
        scrollContent.addView(body)

        // Comments section — built once, hidden until the user requests it.
        val commentsSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val commentsTopRule = View(context).apply {
            background = context.getDrawable(R.drawable.divider_rule)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(20); bottomMargin = dp(16) }
        }
        commentsSection.addView(commentsTopRule)
        commentsSection.addView(makeEyebrowRow("Comments"))
        val commentsBody = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setLineSpacing(dp(5).toFloat(), 1f)
            setLetterSpacing(0.005f)
            typeface = Typeface.SERIF
            setTextIsSelectable(true)
            val pad = dp(12)
            setPadding(0, pad, 0, dp(8))
        }
        commentsSection.addView(commentsBody)
        scrollContent.addView(commentsSection)

        if (!captureNote.isNullOrBlank()) {
            val note = TextView(context).apply {
                text = captureNote
                setTextColor(context.getColor(R.color.text_quaternary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setLetterSpacing(0.10f)
                isAllCaps = true
                typeface = Typeface.SANS_SERIF
                setPadding(0, dp(14), 0, dp(2))
            }
            scrollContent.addView(note)
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(scrollContent)
        card.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ──────────────── footer rule + actions ────────────────
        val footerRule = View(context).apply {
            background = context.getDrawable(R.drawable.divider_rule)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(12) }
        }
        card.addView(footerRule)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Push action buttons to the trailing edge.
        footer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        // Copy — outlined ghost (impeccable's pill).
        val copyBtn = Button(context).apply {
            text = context.getString(R.string.summary_copy)
            isAllCaps = false
            setTextColor(context.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLetterSpacing(0.005f)
            typeface = Typeface.SANS_SERIF
            background = context.getDrawable(R.drawable.btn_ghost_bg)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            stateListAnimator = null
            minWidth = 0; minHeight = 0
            setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val toCopy = buildString {
                    append(summaryText)
                    commentsSummary?.let {
                        append("\n\nComments\n")
                        append(it)
                    }
                }
                cm.setPrimaryClip(ClipData.newPlainText("RedditTLDR summary", toCopy))
                Toast.makeText(context, R.string.summary_copied, Toast.LENGTH_SHORT).show()
            }
        }
        footer.addView(copyBtn)

        // ──────────────── primary action: Summarize comments ────────────────
        var primaryBtn: Button? = null
        if (onSummarizeComments != null) {
            footer.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })
            primaryBtn = Button(context).apply {
                text = "Summarize comments"
                isAllCaps = false
                setTextColor(context.getColor(R.color.btn_primary_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLetterSpacing(0.005f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                background = context.getDrawable(R.drawable.btn_primary_bg)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                stateListAnimator = null
                minWidth = 0; minHeight = 0
                elevation = dp(1).toFloat()
            }
            footer.addView(primaryBtn)

            primaryBtn.setOnClickListener {
                val cb = onSummarizeComments
                primaryBtn.isEnabled = false
                primaryBtn.text = "Summarizing…"
                primaryBtn.alpha = 0.65f
                coroutineScope.launch {
                    val result = cb()
                    result.fold(
                        onSuccess = { res ->
                            commentsSummary = res.summary
                            commentsBody.text = res.summary
                            commentsSection.visibility = View.VISIBLE
                            primaryBtn.visibility = View.GONE
                            scroll.post { scroll.smoothScrollTo(0, commentsSection.top) }
                        },
                        onFailure = { e ->
                            primaryBtn.isEnabled = true
                            primaryBtn.text = "Summarize comments"
                            primaryBtn.alpha = 1f
                            Toast.makeText(
                                context,
                                e.message ?: "Failed to summarize comments",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
        }
        card.addView(footer)

        // ──────────────── window setup + entrance ────────────────
        val cardParams = FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.82f).toInt(),
            Gravity.CENTER
        )
        container.addView(card, cardParams)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0f
            gravity = Gravity.CENTER
        }

        // Single deliberate entrance — fade + subtle lift + 0.985→1 scale.
        // No bounce, no overshoot. PathInterpolator approximates the
        // cubic-bezier(0.16, 1, 0.3, 1) ease-out used in the mockups.
        container.alpha = 0f
        card.scaleX = 0.985f
        card.scaleY = 0.985f
        card.translationY = -dp(6).toFloat()
        windowManager.addView(container, lp)
        root = container

        val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        container.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(ease).start()
        card.animate()
            .scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(ENTER_MS).setInterpolator(ease).start()
    }

    fun detach() {
        root?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        root = null
    }

    /** Eyebrow row — small accent dot + small-caps tracked label. */
    private fun makeEyebrowRow(label: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            background = context.getDrawable(R.drawable.eyebrow_dot)
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6))
                .apply { rightMargin = dp(8) }
        }
        val tv = TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setLetterSpacing(0.20f)
            isAllCaps = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        row.addView(dot)
        row.addView(tv)
        return row
    }

    private fun dismiss() {
        val v = root ?: return
        v.animate()
            .alpha(0f)
            .setDuration(EXIT_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                detach()
                onDismiss()
            }
            .start()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    companion object {
        // Asymmetric enter/exit per emil's principle: enter slower (deliberate),
        // exit faster (out of the way). Both ease out, neither overshoots.
        private const val ENTER_MS = 340L
        private const val EXIT_MS = 160L
    }
}
