package com.personal.pixelagent.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.pixelagent.ui.PermissionReadinessState

@Composable
fun OnboardingCard(
    hasApiKey: Boolean,
    isValidatingKey: Boolean,
    permissions: PermissionReadinessState,
    onValidateAndSaveKey: (String) -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showManualKeyField by rememberSaveable { mutableStateOf(false) }
    var manualKeyText by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Finish Pixel Gatekeeper Setup",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Complete these once—this card disappears permanently as soon as Gatekeeper is armed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Step 1: Gemini API Key
            SetupStepRow(
                icon = Icons.Outlined.Key,
                title = "1. Gemini API Key",
                subtitle = if (hasApiKey) {
                    "Stored encrypted in Android Keystore"
                } else {
                    "Paste from clipboard in 1 tap or run deploy_and_grant_pixel.sh"
                },
                completed = hasApiKey,
                trailing = {
                    if (!hasApiKey) {
                        FilledTonalButton(
                            enabled = !isValidatingKey,
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipText = clipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    ?.trim()
                                    .orEmpty()
                                if (clipText.isNotEmpty()) {
                                    onValidateAndSaveKey(clipText)
                                } else {
                                    showManualKeyField = true
                                }
                            }
                        ) {
                            if (isValidatingKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Checking")
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Paste")
                            }
                        }
                    }
                }
            )

            if (!hasApiKey) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showManualKeyField = !showManualKeyField }) {
                        Text(if (showManualKeyField) "Hide manual entry" else "Type key manually")
                    }
                }

                AnimatedVisibility(visible = showManualKeyField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualKeyText,
                            onValueChange = { manualKeyText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Gemini API Key") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Button(
                            enabled = manualKeyText.isNotBlank() && !isValidatingKey,
                            onClick = { onValidateAndSaveKey(manualKeyText) }
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Step 2: Notification Listener
            SetupStepRow(
                icon = Icons.Outlined.NotificationsActive,
                title = "2. Notification Interception",
                subtitle = if (permissions.listenerEnabled) {
                    "Intercepting & triaging incoming notifications"
                } else {
                    "Allow Pixel AI Gatekeeper to read & clear raw notifications"
                },
                completed = permissions.listenerEnabled,
                trailing = {
                    if (!permissions.listenerEnabled) {
                        FilledTonalButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        ) {
                            Text("Enable")
                        }
                    }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Step 3: Modes / Do Not Disturb Policy
            SetupStepRow(
                icon = Icons.Outlined.ShieldMoon,
                title = "3. Default-Silent DND (Calls Exempt)",
                subtitle = if (permissions.dndPolicyGranted) {
                    "Unvetted apps muted; Pixel Call Screen & Alarms exempt"
                } else {
                    "Grant Modes access so only important alerts ring through"
                },
                completed = permissions.dndPolicyGranted,
                trailing = {
                    if (!permissions.dndPolicyGranted) {
                        FilledTonalButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        ) {
                            Text("Allow")
                        }
                    }
                }
            )

            val allRuntimeGranted = permissions.micGranted &&
                permissions.notificationsGranted &&
                permissions.contactsGranted &&
                permissions.smsGranted &&
                permissions.callLogGranted &&
                permissions.calendarGranted

            if (!allRuntimeGranted) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SetupStepRow(
                    icon = Icons.Outlined.Tune,
                    title = "Voice, Contacts & History Context",
                    subtitle = "Grants Mic, Contacts, SMS, Call Log & Calendar for smart triage",
                    completed = false,
                    trailing = {
                        FilledTonalButton(onClick = onRequestRuntimePermissions) {
                            Text("Grant")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    completed: Boolean,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (completed) Icons.Outlined.CheckCircle else icon,
            contentDescription = null,
            tint = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}
