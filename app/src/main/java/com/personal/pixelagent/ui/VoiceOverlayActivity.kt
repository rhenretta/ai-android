package com.personal.pixelagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.ui.theme.PixelAgentTheme
import com.personal.pixelagent.voice.LiveVoiceForegroundService
import com.personal.pixelagent.voice.LiveVoiceSessionState
import com.personal.pixelagent.voice.VoiceSessionPhase

/**
 * Translucent floating synchronous voice sheet launched from the 4x1 Home-Screen Widget,
 * Live Summary notification `Talk` action, or Main Workspace Mic button.
 * Satisfies Android 14–16 foreground microphone start rules cleanly without opening the full app.
 */
class VoiceOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = applicationContext as PixelAgentApp

        setContent {
            PixelAgentTheme {
                val sessionState by LiveVoiceForegroundService.sessionState.collectAsStateWithLifecycle()
                val settingsState by app.container.settingsStore.state.collectAsStateWithLifecycle()
                var micPermissionDenied by remember { mutableStateOf(false) }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        micPermissionDenied = false
                        LiveVoiceForegroundService.start(this)
                    } else {
                        micPermissionDenied = true
                    }
                }

                LaunchedEffect(Unit) {
                    val hasMic = ContextCompat.checkSelfPermission(
                        this@VoiceOverlayActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasMic) {
                        if (!sessionState.isActive) {
                            LiveVoiceForegroundService.start(this@VoiceOverlayActivity)
                        }
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                val isPrivate = sessionState.isPrivateMode || settingsState.privateModeActive

                FloatingVoiceSheetContent(
                    state = sessionState,
                    isPrivateMode = isPrivate,
                    micPermissionDenied = micPermissionDenied,
                    onDismissScrim = { finish() },
                    onToggleMute = { LiveVoiceForegroundService.toggleMute(this) },
                    onExitPrivateMode = { LiveVoiceForegroundService.exitPrivateMode(this) },
                    onSwitchToText = {
                        LiveVoiceForegroundService.stop(this)
                        val textIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(MainActivity.EXTRA_FOCUS_KEYBOARD, true)
                        }
                        startActivity(textIntent)
                        finish()
                    },
                    onEndVoice = {
                        LiveVoiceForegroundService.stop(this)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun FloatingVoiceSheetContent(
    state: LiveVoiceSessionState,
    isPrivateMode: Boolean,
    micPermissionDenied: Boolean,
    onDismissScrim: () -> Unit,
    onToggleMute: () -> Unit,
    onExitPrivateMode: () -> Unit,
    onSwitchToText: () -> Unit,
    onEndVoice: () -> Unit
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val sheetInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismissScrim
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = sheetInteraction,
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Drag handle visual cue
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // Voice Persona & Session Phase Pill
                val prefix = if (isPrivateMode) "Private · " else ""
                val phaseLabel = when {
                    micPermissionDenied -> "Microphone Permission Needed"
                    state.phase == VoiceSessionPhase.CONNECTING -> "${prefix}Connecting · ${state.activeVoiceName}"
                    state.phase == VoiceSessionPhase.LISTENING -> "${prefix}Listening · ${state.activeVoiceName}"
                    state.phase == VoiceSessionPhase.THINKING -> "${prefix}Thinking · ${state.activeVoiceName}"
                    state.phase == VoiceSessionPhase.SPEAKING -> "${prefix}Speaking · ${state.activeVoiceName}"
                    state.phase == VoiceSessionPhase.MUTED -> "${prefix}Mic Muted · ${state.activeVoiceName}"
                    state.phase == VoiceSessionPhase.ERROR -> "Connection Error"
                    else -> "${prefix}Live · ${state.activeVoiceName}"
                }

                val isError = state.phase == VoiceSessionPhase.ERROR || micPermissionDenied
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when {
                        isError -> MaterialTheme.colorScheme.errorContainer
                        isPrivateMode -> Color(0xFF4C1D95)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (isPrivateMode && !isError) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFFE9D5FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = phaseLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                isError -> MaterialTheme.colorScheme.onErrorContainer
                                isPrivateMode -> Color(0xFFFAF5FF)
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                // 5-Bar Live Cadence Waveform
                VoiceCadenceWaveform(
                    rmsLevel = state.rmsLevel,
                    phase = state.phase,
                    isPrivateMode = isPrivateMode
                )

                // Rolling 2-line live transcript or explicit error
                val displayTranscript = when {
                    micPermissionDenied -> "Microphone permission was denied. Grant audio access to talk synchronously."
                    state.phase == VoiceSessionPhase.ERROR -> state.errorMessage ?: "Gemini Live connection failed."
                    state.rollingTranscript.isNotBlank() -> state.rollingTranscript
                    isPrivateMode -> "Private Mode · Everything from entry until exit will be permanently deleted."
                    else -> "Speak naturally—barge in anytime, or ask to go into private mode."
                }

                Text(
                    text = displayTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )

                // Unmistakable Private Mode Exit & Wipe Chip inside Live Voice Sheet
                if (isPrivateMode && !isError) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF3B0764),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onExitPrivateMode() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFFD8B4FE),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Erased on exit · Tap to Exit & Wipe",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFFAF5FF)
                            )
                        }
                    }
                } else if (!state.latestActionReceipt.isNullOrBlank()) {
                    // Ephemeral Action Receipt Pill (when tool runs mid-voice)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = state.latestActionReceipt,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // Three >= 48dp Thumb-Zone Controls: Mute, Switch to Text, End Voice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(
                            imageVector = if (state.isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.isMuted) "Unmute" else "Mute")
                    }

                    FilledTonalButton(
                        onClick = onSwitchToText,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Type")
                    }

                    Button(
                        onClick = onEndVoice,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CallEnd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("End")
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCadenceWaveform(
    rmsLevel: Float,
    phase: VoiceSessionPhase,
    isPrivateMode: Boolean
) {
    val infinite = rememberInfiniteTransition(label = "voice_pulse")
    val idleBreath by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_factor"
    )

    val weights = listOf(0.45f, 0.8f, 1.0f, 0.75f, 0.5f)

    Row(
        modifier = Modifier.height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        weights.forEachIndexed { idx, factor ->
            val targetFraction = when (phase) {
                VoiceSessionPhase.SPEAKING, VoiceSessionPhase.LISTENING -> {
                    (0.22f + rmsLevel * factor * 0.85f).coerceIn(0.18f, 1f)
                }
                VoiceSessionPhase.THINKING, VoiceSessionPhase.CONNECTING -> {
                    (0.2f + idleBreath * factor * 0.65f).coerceIn(0.2f, 0.9f)
                }
                else -> 0.18f
            }
            val animatedFraction by animateFloatAsState(
                targetValue = targetFraction,
                animationSpec = tween(durationMillis = 90 + idx * 15),
                label = "bar_$idx"
            )

            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height((52f * animatedFraction).dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            phase == VoiceSessionPhase.ERROR -> MaterialTheme.colorScheme.error
                            isPrivateMode -> Color(0xFF8B5CF6)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
            )
        }
    }
}
