package com.stanley.reddittldr

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
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
            MaterialTheme(colorScheme = appColorScheme) {
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

    companion object {
        // Impeccable palette wired into Material3 so every default Compose
        // component (TextField, Button, Chip, etc.) draws from our tinted-neutral
        // system instead of the generic M3 dark defaults.
        val appColorScheme = darkColorScheme(
            background         = Color(0xFF19141C),  // deep tinted dark (scrim family)
            surface            = Color(0xFF2A2C32),  // card_bg
            surfaceVariant     = Color(0xFF232529),  // slightly deeper surface
            onBackground       = Color(0xFFF6F4F0),  // text_primary
            onSurface          = Color(0xFFF6F4F0),  // text_primary
            onSurfaceVariant   = Color(0xFF8B847A),  // text_tertiary
            primary            = Color(0xFFF6F4F0),  // btn_primary_bg
            onPrimary          = Color(0xFF1F2128),  // btn_primary_text
            primaryContainer   = Color(0xFF3A3C45),
            onPrimaryContainer = Color(0xFFF6F4F0),
            secondary          = Color(0xFFD4B26B),  // accent_dot (warm gold)
            onSecondary        = Color(0xFF1F2128),
            outline            = Color(0xFF3D3F47),  // surface_card_hairline
            outlineVariant     = Color(0xFF2E3038),
            error              = Color(0xFFB47A6A),  // muted terracotta error
            onError            = Color(0xFF1F2128),
            scrim              = Color(0xFF19141C),
        )
    }
}
