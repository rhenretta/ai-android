package com.personal.pixelagent.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.pixelagent.data.local.ConversationTurnEntity
import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.TriageTier
import com.personal.pixelagent.data.local.TurnModality
import com.personal.pixelagent.data.local.TurnRole
import com.personal.pixelagent.data.local.UndoActionType
import com.personal.pixelagent.ui.components.HeldSummaryCard
import com.personal.pixelagent.ui.components.MemorySheet
import com.personal.pixelagent.ui.components.OnboardingCard
import com.personal.pixelagent.ui.components.SettingsTraceSheet
import com.personal.pixelagent.ui.components.SpamVaultSheet
import com.personal.pixelagent.ui.theme.PixelAgentTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val focusKeyboardTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkKeyboardExtra(intent)

        setContent {
            PixelAgentTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    viewModel.refreshPermissions()
                }

                WorkspaceScreen(
                    uiState = uiState,
                    focusKeyboardNonce = focusKeyboardTrigger.intValue,
                    onRequestRuntimePermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.POST_NOTIFICATIONS,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.READ_SMS,
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.READ_CALENDAR
                            )
                        )
                    },
                    onValidateAndSaveKey = { viewModel.validateAndSaveApiKey(it) },
                    onToggleGatekeeper = { viewModel.toggleGatekeeper() },
                    onTogglePrivateMode = { viewModel.togglePrivateMode() },
                    onExitPrivateMode = { viewModel.exitPrivateModeAndWipe() },
                    onSetSleepStrictCalls = { viewModel.setSleepStrictCalls(it) },
                    onSetTriageModel = { viewModel.setTriageModel(it) },
                    onSetTriageThinkingLevel = { viewModel.setTriageThinkingLevel(it) },
                    onSelectVoice = { viewModel.switchVoiceFromControls(it) },
                    onSendMessage = { viewModel.sendTextMessage(it) },
                    onLaunchLiveVoice = {
                        startActivity(Intent(this, VoiceOverlayActivity::class.java))
                    },
                    onOpenNotification = { viewModel.openNotificationItem(it) },
                    onTeachNotification = { rec, tier, rule ->
                        viewModel.teachFromNotification(rec, tier, rule)
                    },
                    onClearDigest = { viewModel.clearDigest() },
                    onPurgeSpam = { viewModel.purgeAllSpam() },
                    onCreateMemory = { cat, content -> viewModel.createMemoryFromUi(cat, content) },
                    onUpdateMemory = { id, cat, content -> viewModel.updateMemoryFromUi(id, cat, content) },
                    onDeleteMemory = { viewModel.deactivateMemory(it) },
                    onUndoReceipt = { viewModel.undoActionReceipt(it) },
                    onDismissError = { viewModel.dismissBannerError() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkKeyboardExtra(intent)
    }

    private fun checkKeyboardExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FOCUS_KEYBOARD, false) == true) {
            intent.removeExtra(EXTRA_FOCUS_KEYBOARD)
            focusKeyboardTrigger.intValue += 1
        }
    }

    companion object {
        const val EXTRA_FOCUS_KEYBOARD = "extra_focus_keyboard"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceScreen(
    uiState: MainUiState,
    focusKeyboardNonce: Int,
    onRequestRuntimePermissions: () -> Unit,
    onValidateAndSaveKey: (String) -> Unit,
    onToggleGatekeeper: () -> Unit,
    onTogglePrivateMode: () -> Unit,
    onExitPrivateMode: () -> Unit,
    onSetSleepStrictCalls: (Boolean) -> Unit,
    onSetTriageModel: (String) -> Unit,
    onSetTriageThinkingLevel: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onLaunchLiveVoice: () -> Unit,
    onOpenNotification: (com.personal.pixelagent.data.local.NotificationRecordEntity) -> Unit,
    onTeachNotification: (com.personal.pixelagent.data.local.NotificationRecordEntity, TriageTier, String) -> Unit,
    onClearDigest: () -> Unit,
    onPurgeSpam: () -> Unit,
    onCreateMemory: (MemoryCategory, String) -> Unit,
    onUpdateMemory: (Long, MemoryCategory, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onUndoReceipt: (ConversationTurnEntity) -> Unit,
    onDismissError: () -> Unit
) {
    var draftText by rememberSaveable { mutableStateOf("") }
    var showMemorySheet by rememberSaveable { mutableStateOf(false) }
    var showSpamSheet by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isKeyboardOpen = WindowInsets.isImeVisible
    val listState = rememberLazyListState()

    LaunchedEffect(focusKeyboardNonce) {
        if (focusKeyboardNonce > 0) {
            delay(120)
            runCatching {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    LaunchedEffect(uiState.timeline.size, uiState.isSendingText) {
        val total = uiState.timeline.size + (if (uiState.isSendingText) 1 else 0)
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Minimal 56dp Top Header (Zero Bottom Bar Clutter)
            WorkspaceHeader(
                uiState = uiState,
                onStatusPillClick = {
                    when {
                        uiState.voiceSession.isActive -> onLaunchLiveVoice()
                        uiState.settings.privateModeActive -> onExitPrivateMode()
                        else -> onToggleGatekeeper()
                    }
                },
                onOpenMemories = { showMemorySheet = true },
                onOpenControls = { showControlsSheet = true }
            )

            // Unmistakable Private Mode Active Banner + 1-Tap Exit & Wipe
            AnimatedVisibility(visible = uiState.settings.privateModeActive) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF3B0764),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = Color(0xFFD8B4FE),
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Private Mode Active",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFFAF5FF)
                            )
                            Text(
                                text = "Everything from entry until exit will be permanently deleted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE9D5FF)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF7C3AED),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onExitPrivateMode() }
                        ) {
                            Text(
                                text = "Exit & Wipe",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }

            // Transient In-Memory Confirmation After Exiting Private Mode (Never Persisted)
            AnimatedVisibility(
                visible = !uiState.ephemeralPrivateNotice.isNullOrBlank() && !uiState.settings.privateModeActive
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = uiState.ephemeralPrivateNotice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Explicit Fail-Fast Error Banner (Never Silent)
            AnimatedVisibility(visible = !uiState.bannerError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = uiState.bannerError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissError,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Main Scrollable Canvas: Progressive Setup -> Collapsible Summary -> Conversation Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.showSetupCard) {
                    item(key = "onboarding_card") {
                        OnboardingCard(
                            hasApiKey = uiState.settings.hasApiKey,
                            isValidatingKey = uiState.isValidatingKey,
                            permissions = uiState.permissions,
                            onValidateAndSaveKey = onValidateAndSaveKey,
                            onRequestRuntimePermissions = onRequestRuntimePermissions
                        )
                    }
                }

                if (uiState.digestItems.isNotEmpty() || uiState.spamCountToday > 0) {
                    item(key = "held_summary_card") {
                        HeldSummaryCard(
                            digestItems = uiState.digestItems,
                            spamCountToday = uiState.spamCountToday,
                            isKeyboardOpen = isKeyboardOpen,
                            onOpenItem = onOpenNotification,
                            onTeachItem = onTeachNotification,
                            onClearAll = onClearDigest,
                            onOpenSpamVault = { showSpamSheet = true }
                        )
                    }
                }

                if (uiState.timeline.isEmpty() && !uiState.isSendingText) {
                    item(key = "calm_empty_state") {
                        EmptyConversationWelcome(
                            voiceName = uiState.settings.voiceName,
                            onPickSuggestion = { prompt ->
                                onSendMessage(prompt)
                            }
                        )
                    }
                } else {
                    items(uiState.timeline, key = { it.id }) { turn ->
                        ConversationTurnItem(
                            turn = turn,
                            onUndoReceipt = { onUndoReceipt(turn) }
                        )
                    }
                }

                if (uiState.isSendingText) {
                    item(key = "thinking_indicator") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Thinking...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Thumb-Zone Morphing Composer (Mic when empty -> Send when typing)
            ThumbZoneComposer(
                draftText = draftText,
                onDraftChange = { draftText = it },
                isSending = uiState.isSendingText,
                isVoiceActive = uiState.voiceSession.isActive,
                isPrivateMode = uiState.settings.privateModeActive,
                focusRequester = focusRequester,
                onSend = {
                    val toSend = draftText.trim()
                    if (toSend.isNotEmpty()) {
                        draftText = ""
                        onSendMessage(toSend)
                    }
                },
                onMicClick = onLaunchLiveVoice
            )
        }

        if (showMemorySheet) {
            MemorySheet(
                memories = uiState.activeMemories,
                onCreateMemory = onCreateMemory,
                onUpdateMemory = onUpdateMemory,
                onDeleteMemory = onDeleteMemory,
                onDismiss = { showMemorySheet = false }
            )
        }

        if (showSpamSheet) {
            SpamVaultSheet(
                spamRecords = uiState.spamRecords,
                onRescueItem = { rescued ->
                    val who = rescued.sender.ifBlank { rescued.title }
                    onTeachNotification(
                        rescued,
                        TriageTier.SUMMARY,
                        "Do not disregard messages from '$who' in ${rescued.appName} as spam; include them in the summary or evaluate normally."
                    )
                },
                onPurgeAll = onPurgeSpam,
                onDismiss = { showSpamSheet = false }
            )
        }

        if (showControlsSheet) {
            SettingsTraceSheet(
                settings = uiState.settings,
                permissions = uiState.permissions,
                spamTotalCount = uiState.spamRecords.size,
                recentTraces = uiState.recentTraces,
                onToggleGatekeeper = onToggleGatekeeper,
                onTogglePrivateMode = onTogglePrivateMode,
                onSetSleepStrictCalls = onSetSleepStrictCalls,
                onSetTriageModel = onSetTriageModel,
                onSetTriageThinkingLevel = onSetTriageThinkingLevel,
                onSelectVoice = onSelectVoice,
                onPasteApiKey = onValidateAndSaveKey,
                onOpenSpamVault = { showSpamSheet = true },
                onDismiss = { showControlsSheet = false }
            )
        }
    }
}

@Composable
private fun WorkspaceHeader(
    uiState: MainUiState,
    onStatusPillClick: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenControls: () -> Unit
) {
    val isPrivate = uiState.settings.privateModeActive || uiState.voiceSession.isPrivateMode
    val statusText: String
    val dotColor: Color

    when {
        isPrivate && uiState.voiceSession.isActive -> {
            statusText = "Private · ${uiState.voiceSession.activeVoiceName}"
            dotColor = Color(0xFFD8B4FE)
        }
        isPrivate -> {
            statusText = "Private Mode"
            dotColor = Color(0xFFD8B4FE)
        }
        uiState.voiceSession.isActive -> {
            statusText = "Live · ${uiState.voiceSession.activeVoiceName}"
            dotColor = MaterialTheme.colorScheme.primary
        }
        !uiState.settings.hasApiKey || !uiState.permissions.coreGatekeeperReady -> {
            statusText = "Setup Needed"
            dotColor = MaterialTheme.colorScheme.tertiary
        }
        uiState.settings.gatekeeperEnabled -> {
            statusText = "Gatekeeper On"
            dotColor = Color(0xFF34A853)
        }
        else -> {
            statusText = "Gatekeeper Paused"
            dotColor = MaterialTheme.colorScheme.outline
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Status Pill
        Surface(
            shape = RoundedCornerShape(50),
            color = if (isPrivate) Color(0xFF4C1D95) else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onStatusPillClick() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isPrivate) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        tint = Color(0xFFE9D5FF),
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isPrivate) Color(0xFFFAF5FF) else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Right Actions: Memories + Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onOpenMemories() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    val memCount = uiState.activeMemories.size
                    Text(
                        text = if (memCount > 0) "Rules ($memCount)" else "Rules",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            IconButton(
                onClick = onOpenControls,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Controls and audit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyConversationWelcome(
    voiceName: String,
    onPickSuggestion: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "Quiet watch is active.",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Talk by voice ($voiceName) or type below to define what matters to you, inspect held notifications, or change voices anytime.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val starterPrompts = listOf(
            "Summarize what you're holding right now",
            "Go into private mode",
            "Switch to a warm, breezy voice",
            "What rules do you currently remember?"
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            starterPrompts.forEach { prompt ->
                AssistChip(
                    onClick = { onPickSuggestion(prompt) },
                    label = { Text(prompt) }
                )
            }
        }
    }
}

@Composable
private fun ConversationTurnItem(
    turn: ConversationTurnEntity,
    onUndoReceipt: () -> Unit
) {
    when (turn.role) {
        TurnRole.USER -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 6.dp
                    ),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 310.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (turn.modality == TurnModality.VOICE) {
                            Icon(
                                imageVector = Icons.Outlined.GraphicEq,
                                contentDescription = "Spoken turn",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = turn.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        TurnRole.ASSISTANT -> {
            // Borderless editorial prose (zero chat-bubble clutter)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Text(
                    text = turn.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        TurnRole.ACTION_RECEIPT -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = turn.receiptTitle ?: "Agent Action",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val sub = turn.receiptSubtitle ?: turn.text
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (turn.undoActionType != UndoActionType.NONE) {
                        if (turn.undone) {
                            Text(
                                text = "Undone",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            TextButton(onClick = onUndoReceipt) {
                                Text("Undo")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbZoneComposer(
    draftText: String,
    onDraftChange: (String) -> Unit,
    isSending: Boolean,
    isVoiceActive: Boolean,
    isPrivateMode: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    onMicClick: () -> Unit
) {
    val hasText = draftText.isNotBlank()
    val accentColor = if (isPrivateMode) Color(0xFF7C3AED) else MaterialTheme.colorScheme.primary
    val onAccentColor = if (isPrivateMode) Color.White else MaterialTheme.colorScheme.onPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (draftText.isEmpty()) {
                    Text(
                        text = if (isPrivateMode) {
                            "Message privately (erased on exit)..."
                        } else {
                            "Talk or teach what's important..."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = draftText,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(accentColor),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (hasText && !isSending) onSend() }
                    )
                )
            }
        }

        // Morphing 48dp Action Button: Mic when empty, Send when text entered
        FilledIconButton(
            onClick = {
                if (hasText) {
                    if (!isSending) onSend()
                } else {
                    onMicClick()
                }
            },
            enabled = !isSending,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accentColor,
                contentColor = onAccentColor
            ),
            modifier = Modifier.size(48.dp)
        ) {
            AnimatedContent(targetState = hasText, label = "composer_action_morph") { typing ->
                if (typing) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "Send message"
                    )
                } else {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,
                        contentDescription = "Start synchronous voice chat"
                    )
                }
            }
        }
    }
}
