package com.stanley.reddittldr.ui.overlay

import android.content.Context
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.stanley.reddittldr.R

/**
 * Soft bottom gradient that fades in while the user drags the bubble. Mirrors
 * the visual language of system PiP / chat-head dismiss UIs — focus pulls
 * downward without a heavy full-screen scrim.
 */
class DismissScrimView(context: Context) : View(context) {

    init {
        background = context.getDrawable(R.drawable.dismiss_scrim_bg)
        alpha = 0f
    }

    fun fadeIn() {
        animate()
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun fadeOut(end: () -> Unit) {
        animate()
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(end)
            .start()
    }
}
