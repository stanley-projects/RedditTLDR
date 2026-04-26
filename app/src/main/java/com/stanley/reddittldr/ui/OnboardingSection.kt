package com.stanley.reddittldr.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stanley.reddittldr.R

@Composable
fun OnboardingSection(
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    notificationsEnabled: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val allGranted = accessibilityEnabled && overlayEnabled && notificationsEnabled
    var expanded by remember { mutableStateOf(!allGranted) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> onRefresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResourceOrText(R.string.settings_onboarding_title),
                    style = MaterialTheme.typography.titleMedium
                )
                if (allGranted) {
                    Text(
                        text = stringResourceOrText(R.string.settings_onboarding_all_set),
                        color = Color(0xFF4CAF50)
                    )
                }
            }
            if (allGranted && !expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap to review permissions",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }

            val showCards = expanded || !allGranted
            if (showCards) {
                Spacer(Modifier.height(12.dp))
                PermissionRow(
                    title = stringResourceOrText(R.string.settings_permission_accessibility),
                    granted = accessibilityEnabled,
                    buttonLabel = stringResourceOrText(R.string.settings_open_accessibility),
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    title = stringResourceOrText(R.string.settings_permission_overlay),
                    granted = overlayEnabled,
                    buttonLabel = stringResourceOrText(R.string.settings_open_overlay),
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
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        title = stringResourceOrText(R.string.settings_permission_notifications),
                        granted = notificationsEnabled,
                        buttonLabel = stringResourceOrText(R.string.settings_request_notifications),
                        onAction = {
                            notificationPermissionLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    )
                }
            }
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
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            StatusChip(granted)
        }
        if (!granted) {
            Spacer(Modifier.height(6.dp))
            Button(onClick = onAction) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun StatusChip(granted: Boolean) {
    val bg = if (granted) Color(0xFF2E7D32) else Color(0xFFB71C1C)
    val label = if (granted)
        stringResourceOrText(R.string.settings_permission_granted)
    else
        stringResourceOrText(R.string.settings_permission_not_granted)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun stringResourceOrText(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
