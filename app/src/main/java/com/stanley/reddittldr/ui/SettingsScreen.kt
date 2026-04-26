package com.stanley.reddittldr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.stanley.reddittldr.R
import com.stanley.reddittldr.api.ClaudeRepository
import com.stanley.reddittldr.data.ClaudeModel
import com.stanley.reddittldr.data.SettingsRepository
import com.stanley.reddittldr.data.SummaryLength
import com.stanley.reddittldr.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    Scaffold(contentWindowInsets = WindowInsets.systemBars) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                OnboardingSection(
                    accessibilityEnabled = accessibilityEnabled,
                    overlayEnabled = overlayEnabled,
                    notificationsEnabled = notificationsEnabled,
                    onRefresh = onRefresh
                )

                Spacer(Modifier.height(16.dp))

                // ---------- API key ----------
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_api_key_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                testState = TestState.Idle
                            },
                            placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
                            visualTransformation = if (apiKeyVisible)
                                VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible)
                                            Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_api_key_help),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                settings.apiKey = apiKey.trim()
                                testState = TestState.Idle
                            }) {
                                Text(stringResource(R.string.settings_api_key_save))
                            }
                            TextButton(onClick = {
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
                            }, enabled = apiKey.isNotBlank() && testState != TestState.Testing) {
                                Text(stringResource(R.string.settings_api_key_test))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        when (val s = testState) {
                            TestState.Idle -> {}
                            TestState.Testing -> Text("Testing…", color = Color.Gray)
                            TestState.Ok -> Text(
                                "✓ ${stringResource(R.string.settings_api_key_ok)}",
                                color = Color(0xFF2E7D32)
                            )
                            is TestState.Error -> Text(s.message, color = Color(0xFFB71C1C))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---------- Model ----------
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_model_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
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
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                ClaudeModel.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
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
                }

                Spacer(Modifier.height(16.dp))

                // ---------- Summary length ----------
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_length_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LengthChip(
                                label = stringResource(R.string.settings_length_short),
                                selected = length == SummaryLength.SHORT
                            ) {
                                length = SummaryLength.SHORT
                                settings.summaryLength = SummaryLength.SHORT
                            }
                            LengthChip(
                                label = stringResource(R.string.settings_length_medium),
                                selected = length == SummaryLength.MEDIUM
                            ) {
                                length = SummaryLength.MEDIUM
                                settings.summaryLength = SummaryLength.MEDIUM
                            }
                            LengthChip(
                                label = stringResource(R.string.settings_length_detailed),
                                selected = length == SummaryLength.DETAILED
                            ) {
                                length = SummaryLength.DETAILED
                                settings.summaryLength = SummaryLength.DETAILED
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---------- Debug logs ----------
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Debug logs",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Each tap of the bubble records what the app did — which extraction strategy ran, what was sent to Claude, errors. Share this back when something behaves wrong.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val text = DebugLog.dump()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "RedditTLDR debug log")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, "Share debug log")
                                )
                            }) {
                                Text("Share")
                            }
                            TextButton(onClick = { DebugLog.clear() }) {
                                Text("Clear")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ---------- Footer ----------
                Text(
                    "${stringResource(R.string.settings_version_prefix)} $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_footer_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LengthChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data object Ok : TestState()
    data class Error(val message: String) : TestState()
}
