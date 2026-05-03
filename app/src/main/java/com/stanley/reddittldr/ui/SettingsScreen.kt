package com.stanley.reddittldr.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stanley.reddittldr.R
import com.stanley.reddittldr.api.ClaudeRepository
import com.stanley.reddittldr.data.ClaudeModel
import com.stanley.reddittldr.data.SettingsRepository
import com.stanley.reddittldr.data.SummaryLength
import com.stanley.reddittldr.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Palette constants — same values as the overlay and colors.xml.
private val Surface       = Color(0xFF2A2C32)
private val Hairline      = Color(0xFF3D3F47)
private val TextPrimary   = Color(0xFFF6F4F0)
private val TextSecondary = Color(0xFFD6D2C8)
private val TextTertiary  = Color(0xFF8B847A)
private val TextQuatern   = Color(0xFF6B6862)
private val AccentDot     = Color(0xFFD4B26B)
private val StatusGreen   = Color(0xFF6FB87A)
private val ErrorTerra    = Color(0xFFB47A6A)
private val Background    = Color(0xFF19141C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    notificationsEnabled: Boolean,
    onRefresh: () -> Unit,
    versionName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var model by remember { mutableStateOf(settings.model) }
    var length by remember { mutableStateOf(settings.summaryLength) }

    Scaffold(
        containerColor = Background,
        contentWindowInsets = WindowInsets.systemBars
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(32.dp))

                // ─── App header ───
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(AccentDot, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "REDDITLDR",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.22.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Setup",
                    color = TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(28.dp))

                // ─── Permissions ───
                OnboardingSection(
                    accessibilityEnabled = accessibilityEnabled,
                    overlayEnabled = overlayEnabled,
                    notificationsEnabled = notificationsEnabled,
                    onRefresh = onRefresh
                )

                Spacer(Modifier.height(16.dp))

                // ─── API key ───
                SettingsSection {
                    SectionHeader("Claude API Key")
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            testState = TestState.Idle
                        },
                        placeholder = {
                            Text(
                                stringResource(R.string.settings_api_key_placeholder),
                                color = TextQuatern,
                                fontSize = 14.sp
                            )
                        },
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = null,
                                    tint = TextTertiary
                                )
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentDot,
                            unfocusedBorderColor = Hairline,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextSecondary,
                            cursorColor = AccentDot,
                            focusedContainerColor = Color(0xFF1F2128),
                            unfocusedContainerColor = Color(0xFF1F2128),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_api_key_help),
                        color = TextQuatern,
                        fontSize = 12.sp,
                        letterSpacing = 0.01.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Hairline, thickness = 0.5.dp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Primary — save
                        androidx.compose.material3.Button(
                            onClick = {
                                settings.apiKey = apiKey.trim()
                                testState = TestState.Idle
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TextPrimary,
                                contentColor = Color(0xFF1F2128)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.settings_api_key_save),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                        // Ghost — test
                        TextButton(
                            onClick = {
                                settings.apiKey = apiKey.trim()
                                settings.model = model
                                testState = TestState.Testing
                                scope.launch {
                                    val repo = ClaudeRepository(settings)
                                    val result = withContext(Dispatchers.IO) { repo.testApiKey() }
                                    testState = result.fold(
                                        onSuccess = { TestState.Ok },
                                        onFailure = { TestState.Error(it.message ?: "Failed") }
                                    )
                                }
                            },
                            enabled = apiKey.isNotBlank() && testState != TestState.Testing,
                            colors = ButtonDefaults.textButtonColors(contentColor = TextTertiary)
                        ) {
                            Text(
                                stringResource(R.string.settings_api_key_test),
                                fontSize = 13.sp
                            )
                        }
                    }
                    when (val s = testState) {
                        TestState.Idle -> {}
                        TestState.Testing -> {
                            Spacer(Modifier.height(6.dp))
                            Text("Testing…", color = TextTertiary, fontSize = 12.sp)
                        }
                        TestState.Ok -> {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(StatusGreen, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.settings_api_key_ok),
                                    color = StatusGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        is TestState.Error -> {
                            Spacer(Modifier.height(6.dp))
                            Text(s.message, color = ErrorTerra, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ─── Model ───
                SettingsSection {
                    SectionHeader("Model")
                    Spacer(Modifier.height(14.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = model.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentDot,
                                unfocusedBorderColor = Hairline,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextSecondary,
                                focusedTrailingIconColor = TextTertiary,
                                unfocusedTrailingIconColor = TextTertiary,
                                focusedContainerColor = Color(0xFF1F2128),
                                unfocusedContainerColor = Color(0xFF1F2128),
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(Surface)
                        ) {
                            ClaudeModel.values().forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.displayName,
                                            color = if (option == model) TextPrimary else TextSecondary,
                                            fontWeight = if (option == model) FontWeight.Medium else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        model = option
                                        settings.model = option
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ─── Summary length ───
                SettingsSection {
                    SectionHeader("Summary Length")
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SummaryLength.SHORT to stringResource(R.string.settings_length_short),
                            SummaryLength.MEDIUM to stringResource(R.string.settings_length_medium),
                            SummaryLength.DETAILED to stringResource(R.string.settings_length_detailed)
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = length == value,
                                onClick = {
                                    length = value
                                    settings.summaryLength = value
                                },
                                label = {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        fontWeight = if (length == value) FontWeight.Medium else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TextPrimary,
                                    selectedLabelColor = Color(0xFF1F2128),
                                    containerColor = Color(0xFF1F2128),
                                    labelColor = TextTertiary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = length == value,
                                    borderColor = Hairline,
                                    selectedBorderColor = TextPrimary,
                                    borderWidth = 0.5.dp,
                                    selectedBorderWidth = 0.5.dp
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ─── Debug logs ───
                SettingsSection {
                    SectionHeader("Debug Logs")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Each tap records what the extractor read, what was sent to Claude, and any errors. Share when something misbehaves.",
                        color = TextTertiary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Hairline, thickness = 0.5.dp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(
                            onClick = {
                                val text = DebugLog.dump()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "RedditTLDR debug log")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(send, "Share debug log"))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TextPrimary,
                                contentColor = Color(0xFF1F2128)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Share", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        TextButton(
                            onClick = { DebugLog.clear() },
                            colors = ButtonDefaults.textButtonColors(contentColor = TextTertiary)
                        ) {
                            Text("Clear", fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ─── Footer ───
                Text(
                    "Version $versionName",
                    color = TextQuatern,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_footer_note),
                    color = TextQuatern,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─── Shared composables ───────────────────────────────────────────────────

/**
 * Impeccable section surface: tinted-neutral fill, 22 dp radius, hairline border.
 * Matches the overlay card's visual language in Compose.
 */
@Composable
fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(22.dp))
            .border(0.5.dp, Hairline, RoundedCornerShape(22.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
        content = content
    )
}

/**
 * Eyebrow-style section header — warm-gold accent dot + small-caps tracked label.
 */
@Composable
fun SectionHeader(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(AccentDot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            label.uppercase(),
            color = TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.20.sp
        )
    }
}

private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data object Ok : TestState()
    data class Error(val message: String) : TestState()
}
