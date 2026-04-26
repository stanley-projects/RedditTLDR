package com.stanley.reddittldr.ui.overlay

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.stanley.reddittldr.R

/**
 * The drag-to-dismiss target. Two-layer structure:
 *
 *  - The OUTER FrameLayout is sized to fill the WindowManager window. The window is
 *    intentionally larger than the visible circle so that scale-up animations
 *    don't get clipped by the window bounds.
 *  - The INNER `circle` view holds the actual oval drawable + X icon. Scale and
 *    drawable swaps happen on the inner view only — the outer container stays put.
 *
 *  This is what fixes the "knife-cut at the bottom" appearance you'd see if the
 *  whole view was scaled inside a tight window.
 */
class DismissTargetView(context: Context) : FrameLayout(context) {

    private val circle: FrameLayout = FrameLayout(context).apply {
        background = context.getDrawable(R.drawable.dismiss_target_bg)
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }
        addView(
            icon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        scaleX = 0.5f
        scaleY = 0.5f
    }

    init {
        clipChildren = false
        clipToPadding = false
        val visual = dp(VISUAL_DIAMETER_DP)
        addView(circle, LayoutParams(visual, visual, Gravity.CENTER))
        alpha = 0f
    }

    fun setActive(active: Boolean) {
        circle.background = context.getDrawable(
            if (active) R.drawable.dismiss_target_bg_active
            else R.drawable.dismiss_target_bg
        )
        circle.animate()
            .scaleX(if (active) ACTIVE_SCALE else 1f)
            .scaleY(if (active) ACTIVE_SCALE else 1f)
            .setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun fadeIn() {
        animate()
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        circle.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
    }

    fun fadeOut(end: () -> Unit) {
        animate()
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(end)
            .start()
        circle.animate()
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val VISUAL_DIAMETER_DP = 72
        const val WINDOW_SIZE_DP = 120 // gives 1.22x scale of dp(72) plus breathing room

        private const val ACTIVE_SCALE = 1.22f
    }
}
