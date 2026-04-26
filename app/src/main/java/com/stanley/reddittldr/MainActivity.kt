package com.stanley.reddittldr

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stanley.reddittldr.data.SettingsRepository
import com.stanley.reddittldr.ui.SettingsScreen
import com.stanley.reddittldr.util.DebugLog
import com.stanley.reddittldr.util.PermissionState
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository
    private val permissionFlow = MutableStateFlow(
        PermissionSnapshot(accessibility = false, overlay = false, notifications = false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Prevent API key from appearing in recents / screenshots.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        DebugLog.init(this)
        settings = SettingsRepository(this)
        refreshPermissions()

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val snapshot by permissionFlow.collectAsStateWithLifecycle()
                SettingsScreen(
                    settings = settings,
                    accessibilityEnabled = snapshot.accessibility,
                    overlayEnabled = snapshot.overlay,
                    notificationsEnabled = snapshot.notifications,
                    onRefresh = ::refreshPermissions,
                    versionName = versionName
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        permissionFlow.value = PermissionSnapshot(
            accessibility = PermissionState.isAccessibilityEnabled(this),
            overlay = PermissionState.canDrawOverlays(this),
            notifications = PermissionState.hasNotificationPermission(this)
        )
    }

    private data class PermissionSnapshot(
        val accessibility: Boolean,
        val overlay: Boolean,
        val notifications: Boolean
    )
}
