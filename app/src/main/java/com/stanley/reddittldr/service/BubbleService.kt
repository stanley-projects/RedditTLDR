package com.stanley.reddittldr.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.stanley.reddittldr.MainActivity
import com.stanley.reddittldr.R
import com.stanley.reddittldr.api.ClaudeRepository
import com.stanley.reddittldr.data.SettingsRepository
import com.stanley.reddittldr.reddit.ExtractionMethod
import com.stanley.reddittldr.reddit.PostContent
import com.stanley.reddittldr.reddit.RedditJsonClient
import com.stanley.reddittldr.ui.overlay.BubbleView
import com.stanley.reddittldr.ui.overlay.DismissScrimView
import com.stanley.reddittldr.ui.overlay.DismissTargetView
import com.stanley.reddittldr.ui.overlay.SummaryOverlay
import com.stanley.reddittldr.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsRepository
    private lateinit var claude: ClaudeRepository
    private val redditJson = RedditJsonClient()

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var summaryOverlay: SummaryOverlay? = null
    private var currentJob: Job? = null

    private var dismissTarget: DismissTargetView? = null
    private var dismissScrim: DismissScrimView? = null
    private var isOverTarget: Boolean = false
    private var edgeAnimator: ValueAnimator? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = SettingsRepository(this)
        claude = ClaudeRepository(settings)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop button pressed in the notification — same intent as drag-dismiss.
            RedditWatcherService.instance?.markUserDismissed()
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            return START_NOT_STICKY
        }
        showBubble()
        // Don't auto-restart: if we get killed, we'd come back over whatever app
        // the user is currently in, not necessarily Reddit. The watcher will fire
        // a fresh start the next time the user enters Reddit.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        edgeAnimator?.cancel()
        edgeAnimator = null
        hideBubble()
        detachDismissChrome(animated = false)
        summaryOverlay?.detach()
        summaryOverlay = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Bubble lifecycle ----------

    private fun showBubble() {
        if (bubbleView != null) return
        val size = dp(56)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = settings.bubbleX
            val savedY = settings.bubbleY
            if (savedX >= 0 && savedY >= 0) {
                x = savedX
                y = savedY
            } else {
                val metrics = resources.displayMetrics
                x = metrics.widthPixels - size - dp(8)
                y = metrics.heightPixels / 3
            }
        }
        val view = BubbleView(
            context = this,
            layoutParams = params,
            windowManager = windowManager,
            onTap = { onBubbleTap() },
            onDragStart = { onBubbleDragStart() },
            onDragMove = { cx, cy -> onBubbleDragMove(cx, cy) },
            onDragEnd = { cx, cy -> onBubbleDragEnd(cx, cy) }
        )
        try {
            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun hideBubble() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
        bubbleParams = null
    }

    // ---------- Drag-to-dismiss ----------

    private fun onBubbleDragStart() {
        // Cancel any pending edge animation so the user can pick the bubble up
        // mid-spring without fighting the animator.
        edgeAnimator?.cancel()
        edgeAnimator = null
        attachDismissChrome()
    }

    private fun onBubbleDragMove(bubbleCx: Int, bubbleCy: Int) {
        val target = dismissTarget ?: return
        val tcx = targetCenterX()
        val tcy = targetCenterY()
        val distance = hypot((bubbleCx - tcx).toDouble(), (bubbleCy - tcy).toDouble())
        val over = distance < SNAP_RADIUS_PX
        if (over != isOverTarget) {
            isOverTarget = over
            target.setActive(over)
            bubbleView?.setOverTarget(over)
            if (over) hapticTick()
        }
        if (over) {
            // Magnetic lock: pin the bubble to the target's center while inside
            // the snap radius. The user's finger keeps moving (BubbleView writes
            // its own touch-derived position first), but we override here so the
            // bubble visually clings to the X.
            magneticLock(tcx, tcy)
        }
    }

    private fun magneticLock(targetCenterX: Int, targetCenterY: Int) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val newX = targetCenterX - (params.width / 2)
        val newY = targetCenterY - (params.height / 2)
        if (params.x != newX || params.y != newY) {
            params.x = newX
            params.y = newY
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    private fun onBubbleDragEnd(bubbleCx: Int, bubbleCy: Int) {
        if (isOverTarget) {
            performDismissAnimation()
            return
        }
        // Regular drop: settle to nearest screen edge with a soft overshoot.
        bubbleView?.setOverTarget(false)
        detachDismissChrome(animated = true)
        springToNearestEdge()
    }

    private fun performDismissAnimation() {
        // Mark dismissed BEFORE animating — if a Reddit window event fires while the
        // bubble is animating out, we want the watcher to know we're dismissed.
        RedditWatcherService.instance?.markUserDismissed()
        val view = bubbleView
        // The bubble is already locked to target center via magneticLock, so the
        // animation is a clean shrink-and-fade from that position into the X.
        if (view == null) {
            detachDismissChrome(animated = true)
            stopSelf()
            return
        }
        view.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(220)
            .withEndAction {
                detachDismissChrome(animated = true)
                stopSelf()
            }
            .start()
        // Fade the target out alongside the bubble so they exit together.
        dismissTarget?.fadeOut { /* removal handled by detachDismissChrome */ }
    }

    private fun springToNearestEdge() {
        val params = bubbleParams ?: return
        val view = bubbleView ?: return
        val size = params.width
        val screenW = resources.displayMetrics.widthPixels
        val edgeInset = dp(8)
        val targetX = if (params.x + size / 2 < screenW / 2) {
            edgeInset
        } else {
            screenW - size - edgeInset
        }
        if (params.x == targetX) {
            settings.bubbleX = params.x
            settings.bubbleY = params.y
            return
        }
        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                params.x = it.animatedValue as Int
                try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settings.bubbleX = params.x
                    settings.bubbleY = params.y
                    if (edgeAnimator === animation) edgeAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    if (edgeAnimator === animation) edgeAnimator = null
                }
            })
            start()
        }
    }

    private fun attachDismissChrome() {
        attachDismissScrim()
        attachDismissTarget()
    }

    private fun detachDismissChrome(animated: Boolean) {
        if (animated) {
            detachDismissTarget()
            detachDismissScrim()
        } else {
            // Synchronous teardown for service destruction.
            dismissTarget?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            dismissScrim?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            dismissTarget = null
            dismissScrim = null
            isOverTarget = false
        }
    }

    private fun attachDismissTarget() {
        if (dismissTarget != null) return
        // Window is intentionally larger than the visible circle so the scale-up
        // animation isn't clipped at the window edges. The DismissTargetView centers
        // its circle inside this oversized container.
        val windowSize = dp(DismissTargetView.WINDOW_SIZE_DP)
        val params = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Position the WINDOW so its center sits TARGET_CENTER_FROM_BOTTOM_DP
            // up from the screen bottom — that's where the visible circle ends up.
            y = dp(TARGET_CENTER_FROM_BOTTOM_DP) - windowSize / 2
        }
        val view = DismissTargetView(this)
        try {
            windowManager.addView(view, params)
            dismissTarget = view
            isOverTarget = false
            view.fadeIn()
        } catch (_: Exception) {
        }
    }

    private fun detachDismissTarget() {
        val view = dismissTarget ?: return
        dismissTarget = null
        isOverTarget = false
        view.fadeOut {
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
    }

    private fun attachDismissScrim() {
        if (dismissScrim != null) return
        val height = dp(SCRIM_HEIGHT_DP_INT)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        val view = DismissScrimView(this)
        try {
            windowManager.addView(view, params)
            dismissScrim = view
            view.fadeIn()
        } catch (_: Exception) {
        }
    }

    private fun detachDismissScrim() {
        val view = dismissScrim ?: return
        dismissScrim = null
        view.fadeOut {
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
    }

    private fun targetCenterX(): Int {
        val metrics = resources.displayMetrics
        return metrics.widthPixels / 2
    }

    private fun targetCenterY(): Int {
        val metrics = resources.displayMetrics
        // Visible circle center sits TARGET_CENTER_FROM_BOTTOM_DP above the screen bottom.
        return metrics.heightPixels - dp(TARGET_CENTER_FROM_BOTTOM_DP)
    }

    private fun hapticTick() {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    // ---------- Tap flow ----------

    private fun onBubbleTap() {
        if (currentJob?.isActive == true) return
        if (summaryOverlay != null) return
        val bv = bubbleView ?: return

        if (settings.apiKey.isBlank()) {
            Toast.makeText(this, R.string.toast_no_api_key, Toast.LENGTH_LONG).show()
            launchSettings()
            return
        }

        DebugLog.startSession("bubble tap")
        bv.setLoading(true)
        currentJob = scope.launch {
            try {
                val svc = RedditWatcherService.instance
                if (svc == null) {
                    DebugLog.log("tap", "RedditWatcherService.instance is null — cannot read tree")
                    DebugLog.finishCurrent("FAIL: no accessibility instance")
                    failWithToast(R.string.toast_cant_read_post)
                    return@launch
                }
                val post = withContext(Dispatchers.Default) { svc.extractCurrentPost() }
                DebugLog.logKv(
                    "tap",
                    "extractor" to post.extractionMethod.name,
                    "isPartial" to post.isPartial,
                    "titleLen" to (post.title?.length ?: 0),
                    "bodyLen" to post.body.length
                )
                if (post.extractionMethod == ExtractionMethod.FAILED || post.body.isBlank()) {
                    DebugLog.finishCurrent("FAIL: extraction returned no body")
                    failWithToast(R.string.toast_cant_read_post)
                    return@launch
                }
                val result = claude.summarize(post)
                result.fold(
                    onSuccess = { summary ->
                        DebugLog.finishCurrent("SUCCESS via ${post.extractionMethod.name}")
                        showSummary(summary, post)
                    },
                    onFailure = { e ->
                        DebugLog.finishCurrent("FAIL: claude — ${e.message ?: e.javaClass.simpleName}")
                        bv.setLoading(false)
                        Toast.makeText(
                            this@BubbleService,
                            e.message ?: "Claude error",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                DebugLog.finishCurrent("FAIL: exception — ${e.message ?: e.javaClass.simpleName}")
                bv.setLoading(false)
                Toast.makeText(this@BubbleService, e.message ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun failWithToast(resId: Int) {
        bubbleView?.setLoading(false)
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun showSummary(summary: String, post: PostContent) {
        bubbleView?.setLoading(false)
        // Comments are offered when we have either a direct postId from the
        // accessibility tree, or a subreddit + title we can search with. Subreddit
        // detection now reads the first "r/<sub>" in top-to-bottom visible order,
        // so it points at the actual post — not a sidebar promo.
        val canSummarizeComments = post.postId != null ||
                (post.subreddit != null && (post.title?.length ?: 0) >= 10)
        val commentCb: (suspend () -> Result<Pair<String, Int>>)? = if (!canSummarizeComments) null else {
            {
                val resolvedId = post.postId ?: run {
                    val sub = post.subreddit
                    val t = post.title
                    if (sub != null && t != null) {
                        redditJson.searchPostId(sub, t).getOrNull()
                    } else null
                }
                if (resolvedId == null) {
                    Result.failure(Exception("Couldn't identify the post for comments."))
                } else {
                    val fetched = redditJson.fetchComments(resolvedId)
                    fetched.fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) Result.failure(Exception("No usable comments found."))
                            else claude.summarizeComments(list, post.title).map { it to list.size }
                        },
                        onFailure = { Result.failure(it) }
                    )
                }
            }
        }
        val screens = post.forwardScrolls + 1
        val captureNote = if (post.linesCaptured > 0) {
            "Captured ${post.linesCaptured} lines across $screens screen${if (screens == 1) "" else "s"}"
        } else null
        summaryOverlay = SummaryOverlay(
            context = this,
            windowManager = windowManager,
            coroutineScope = scope,
            summaryText = summary,
            isPartial = post.isPartial,
            captureNote = captureNote,
            onSummarizeComments = commentCb,
            onDismiss = { summaryOverlay = null }
        ).also { it.attach() }
    }

    private fun launchSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, BubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_close,
                getString(R.string.notification_action_stop),
                stopPi
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val SNAP_RADIUS_PX: Float
        get() = dp(96).toFloat()

    companion object {
        const val ACTION_START = "com.stanley.reddittldr.START_BUBBLE"
        const val ACTION_STOP = "com.stanley.reddittldr.STOP_BUBBLE"
        private const val CHANNEL_ID = "reddittldr_bubble"
        private const val NOTIFICATION_ID = 4201
        // Distance from screen bottom to the CENTER of the visible dismiss circle.
        // Pulled up high enough that the circle clears the system nav bar / app
        // tab bars even on devices with three-button navigation.
        private const val TARGET_CENTER_FROM_BOTTOM_DP = 128
        private const val SCRIM_HEIGHT_DP_INT = 280
    }
}
