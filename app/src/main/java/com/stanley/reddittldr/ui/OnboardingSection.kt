package com.stanley.reddittldr.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stanley.reddittldr.R

// Palette — mirrors SettingsScreen values.
private val AccentDot   = Color(0xFFD4B26B)
private val Hairline    = Color(0xFF3D3F47)
private val TextPrimary = Color(0xFFF6F4F0)
private val TextSecond  = Color(0xFFD6D2C8)
private val TextTertiary = Color(0xFF8B847A)
private val StatusGreen = Color(0xFF6FB87A)
private val StatusWarn  = Color(0xFFB47A6A)  // muted terracotta for not-granted

@Composable
fun OnboardingSection(
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    notificationsEnabled: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val allGranted = accessibilityEnabled && overlayEnabled && notificationsEnabled

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> onRefresh() }

    SettingsSection {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader("Permissions")
            if (allGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(StatusGreen, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ALL SET",
                        color = StatusGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Hairline, thickness = 0.5.dp)
        Spacer(Modifier.height(14.dp))

        PermissionRow(
            title = androidx.compose.ui.res.stringResource(R.string.settings_permission_accessibility),
            granted = accessibilityEnabled,
            buttonLabel = androidx.compose.ui.res.stringResource(R.string.settings_open_accessibility),
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Hairline, thickness = 0.5.dp)
        Spacer(Modifier.height(12.dp))

        PermissionRow(
            title = androidx.compose.ui.res.stringResource(R.string.settings_permission_overlay),
            granted = overlayEnabled,
            buttonLabel = androidx.compose.ui.res.stringResource(R.string.settings_open_overlay),
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Hairline, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))

            PermissionRow(
                title = androidx.compose.ui.res.stringResource(R.string.settings_permission_notifications),
                granted = notificationsEnabled,
                buttonLabel = androidx.compose.ui.res.stringResource(R.string.settings_request_notifications),
                onAction = {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    buttonLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = if (granted) TextSecond else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (granted) FontWeight.Normal else FontWeight.Medium
        )
        if (granted) {
            GrantedBadge()
        } else {
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = AccentDot),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 6.dp
                )
            ) {
                Text(buttonLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun GrantedBadge() {
    Box(
        modifier = Modifier
            .background(
                color = Color(0xFF1A2B1C),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.settings_permission_granted).uppercase(),
            color = StatusGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.10.sp
        )
    }
}
