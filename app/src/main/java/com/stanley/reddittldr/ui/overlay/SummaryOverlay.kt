package com.stanley.reddittldr.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stanley.reddittldr.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds and manages a full-screen overlay window containing the summary card.
 * The caller owns the WindowManager lifecycle via [attach] / [detach].
 *
 * If [onSummarizeComments] is non-null, the footer shows a "Summarize comments"
 * button. The fetch + Claude call only fire when the user taps it - no work
 * is done up front. After it completes, the result is appended below the post
 * summary in the same scrollable area.
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
        val container = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.scrim))
            setOnClickListener { dismiss() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.summary_card_bg)
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.summary_title)
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            background = null
            setColorFilter(context.getColor(R.color.text_primary))
            setOnClickListener { dismiss() }
        }
        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        if (isPartial) {
            val banner = TextView(context).apply {
                text = context.getString(R.string.summary_partial_banner)
                setTextColor(context.getColor(R.color.partial_amber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = context.getDrawable(R.drawable.partial_banner_bg)
                val pad = dp(10)
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            val bannerParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            card.addView(banner, bannerParams)
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val body = TextView(context).apply {
            text = summaryText
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextIsSelectable(true)
            val pad = dp(16)
            setPadding(0, pad, 0, pad)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        scrollContent.addView(body)

        // Inline comments section (hidden until populated).
        val commentsHeader = TextView(context).apply {
            text = "Comments"
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            alpha = 0.7f
            visibility = View.GONE
            val pad = dp(8)
            setPadding(0, pad, 0, pad)
        }
        val commentsBody = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
            visibility = View.GONE
            val pad = dp(4)
            setPadding(0, pad, 0, pad)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val commentsNote = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            alpha = 0.55f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(12))
        }
        scrollContent.addView(commentsHeader)
        scrollContent.addView(commentsBody)
        scrollContent.addView(commentsNote)

        if (!captureNote.isNullOrBlank()) {
            val note = TextView(context).apply {
                text = captureNote
                setTextColor(context.getColor(R.color.text_primary))
                alpha = 0.55f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                val pad = dp(4)
                setPadding(0, dp(8), 0, pad)
            }
            scrollContent.addView(note)
        }

        val scroll = ScrollView(context)
        scroll.addView(scrollContent)
        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        card.addView(scroll, scrollParams)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        // "Summarize comments" - only added if a callback was provided.
        var commentsBtn: Button? = null
        var commentsSpinner: ProgressBar? = null
        if (onSummarizeComments != null) {
            commentsBtn = Button(context).apply {
                text = "Summarize comments"
                isAllCaps = false
            }
            commentsSpinner = ProgressBar(context).apply {
                visibility = View.GONE
                val s = dp(20)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    leftMargin = dp(8)
                }
            }
            footer.addView(commentsBtn)
            footer.addView(commentsSpinner)
        }

        // Flexible spacer to push Copy/Close to the right edge.
        val flex = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        footer.addView(flex)

        val copyBtn = Button(context).apply {
            text = context.getString(R.string.summary_copy)
            setOnClickListener {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val toCopy = buildString {
                    append(summaryText)
                    commentsSummary?.let {
                        append("\n\nComments\n")
                        append(it)
                    }
                }
                cb.setPrimaryClip(ClipData.newPlainText("RedditTLDR summary", toCopy))
                Toast.makeText(context, R.string.summary_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val closeTextBtn = Button(context).apply {
            text = context.getString(R.string.summary_close)
            setOnClickListener { dismiss() }
        }
        footer.addView(copyBtn)
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        }
        footer.addView(spacer)
        footer.addView(closeTextBtn)
        card.addView(footer)

        commentsBtn?.setOnClickListener {
            val cb = onSummarizeComments ?: return@setOnClickListener
            commentsBtn.isEnabled = false
            commentsBtn.text = "Loading..."
            commentsSpinner?.visibility = View.VISIBLE
            coroutineScope.launch {
                val result = cb()
                result.fold(
                    onSuccess = { res ->
                        commentsSummary = res.summary
                        commentsBody.text = res.summary
                        commentsHeader.visibility = View.VISIBLE
                        commentsBody.visibility = View.VISIBLE
                        commentsNote.text = "Comments summary"
                        commentsNote.visibility = View.VISIBLE
                        // Button has done its job - remove it.
                        commentsBtn.visibility = View.GONE
                        commentsSpinner?.visibility = View.GONE
                        scroll.post { scroll.smoothScrollTo(0, commentsHeader.top) }
                    },
                    onFailure = { e ->
                        commentsBtn.isEnabled = true
                        commentsBtn.text = "Summarize comments"
                        commentsSpinner?.visibility = View.GONE
                        Toast.makeText(
                            context,
                            e.message ?: "Failed to summarize comments",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }

        val cardParams = FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.8f).toInt(),
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

        container.alpha = 0f
        card.scaleX = 0.95f
        card.scaleY = 0.95f
        windowManager.addView(container, lp)
        root = container
        container.animate().alpha(1f).setDuration(140).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    fun detach() {
        root?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        root = null
    }

    private fun dismiss() {
        val v = root ?: return
        v.animate().alpha(0f).setDuration(120).withEndAction {
            detach()
            onDismiss()
        }.start()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
