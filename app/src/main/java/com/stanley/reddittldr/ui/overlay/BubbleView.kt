package com.stanley.reddittldr.ui.overlay

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.stanley.reddittldr.R
import kotlin.math.abs

/**
 * Draggable circular bubble. Callbacks:
 *   - [onTap] — short taps
 *   - [onDragStart] — fired once movement crosses the slop threshold
 *   - [onDragMove] — continuous updates with the bubble's screen-center coords
 *   - [onDragEnd] — release after a drag; returns (centerX, centerY)
 *
 * Caller is expected to swap between the icon and progress indicator with [setLoading].
 */
class BubbleView(
    context: Context,
    private val layoutParams: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onDragMove: (Int, Int) -> Unit,
    private val onDragEnd: (Int, Int) -> Unit
) : FrameLayout(context) {

    private val icon: ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_bubble)
        val pad = dp(10).toInt()
        setPadding(pad, pad, pad, pad)
    }

    private val spinner: ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        val pad = dp(10).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var isDragging = false

    init {
        background = context.getDrawable(R.drawable.bubble_bg)
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(spinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    fun setLoading(loading: Boolean) {
        icon.visibility = if (loading) View.GONE else View.VISIBLE
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        isClickable = !loading
    }

    /** Visual feedback for "magnetically captured by the dismiss target". */
    fun setOverTarget(over: Boolean) {
        animate()
            .scaleX(if (over) 0.82f else 1f)
            .scaleY(if (over) 0.82f else 1f)
            .setDuration(160)
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX)) {
                    isDragging = true
                    onDragStart()
                }
                if (isDragging) {
                    layoutParams.x = downX + dx.toInt()
                    layoutParams.y = downY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(this, layoutParams)
                    } catch (_: Exception) {
                    }
                    onDragMove(centerX(), centerY())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    performClick()
                    onTap()
                } else {
                    onDragEnd(centerX(), centerY())
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun centerX(): Int = layoutParams.x + (layoutParams.width / 2)
    private fun centerY(): Int = layoutParams.y + (layoutParams.height / 2)

    private fun dp(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

    companion object {
        private const val TAP_SLOP_PX = 24
    }
}
