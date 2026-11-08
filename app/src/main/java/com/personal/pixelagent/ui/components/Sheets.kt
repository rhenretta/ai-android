package com.personal.pixelagent.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.MemoryEntity
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.TraceLogEntity
import com.personal.pixelagent.data.local.TriageTier
import com.personal.pixelagent.data.settings.AgentSettingsSnapshot
import com.personal.pixelagent.network.GeminiVoicesClient
import com.personal.pixelagent.ui.PermissionReadinessState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemorySheet(
    memories: List<MemoryEntity>,
    onCreateMemory: (MemoryCategory, String) -> Unit,
    onUpdateMemory: (Long, MemoryCategory, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = remember(memories) { memories.groupBy { it.category } }

    var isCreatingNew by rememberSaveable { mutableStateOf(false) }
    var newRuleText by rememberSaveable { mutableStateOf("") }
    var newRuleCategory by rememberSaveable { mutableStateOf(MemoryCategory.PREFERENCE) }

    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingText by rememberSaveable { mutableStateOf("") }
    var editingCategory by rememberSaveable { mutableStateOf(MemoryCategory.PREFERENCE) }

    val categoryLabels = remember {
        listOf(
            MemoryCategory.STANDING_DIRECTIVE to "Standing Rule",
            MemoryCategory.PREFERENCE to "Preference",
            MemoryCategory.RELATIONSHIP to "Relationship",
            MemoryCategory.ACTIVE_EXPECTATION to "Expectation"
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Rules & Memories",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AssistChip(
                    onClick = {
                        isCreatingNew = !isCreatingNew
                        editingId = null
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(if (isCreatingNew) "Close" else "New Rule") }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your assistant can inspect, create, edit, and delete these rules directly in voice or text chat—or you can adjust them manually below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(visible = isCreatingNew) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = newRuleText,
                            onValueChange = { newRuleText = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            placeholder = { Text("Write a self-contained rule or relationship note...") },
                            minLines = 2,
                            maxLines = 4
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            categoryLabels.forEach { (cat, label) ->
                                val selected = newRuleCategory == cat
                                FilterChip(
                                    selected = selected,
                                    onClick = { newRuleCategory = cat },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    isCreatingNew = false
                                    newRuleText = ""
                                }
                            ) {
                                Text("Cancel")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val clean = newRuleText.trim()
                                    if (clean.isNotEmpty()) {
                                        onCreateMemory(newRuleCategory, clean)
                                        newRuleText = ""
                                        isCreatingNew = false
                                    }
                                },
                                enabled = newRuleText.isNotBlank()
                            ) {
                                Text("Save Rule")
                            }
                        }
                    }
                }
            }

            if (memories.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        text = "No custom rules yet. Tap 'New Rule' above or tell your assistant in voice/text who matters to you and how notifications should be handled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(bottom = 24.dp)
                ) {
                    MemoryCategory.entries.forEach { cat ->
                        val itemsForCat = grouped[cat].orEmpty()
                        if (itemsForCat.isNotEmpty()) {
                            item(key = "header_${cat.name}") {
                                Text(
                                    text = when (cat) {
                                        MemoryCategory.STANDING_DIRECTIVE -> "Standing Directives"
                                        MemoryCategory.RELATIONSHIP -> "People & Relationships"
                                        MemoryCategory.PREFERENCE -> "Notification Preferences"
                                        MemoryCategory.ACTIVE_EXPECTATION -> "Active Expectations"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(itemsForCat, key = { it.id }) { mem ->
                                val isEditing = editingId == mem.id
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isEditing) {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isEditing) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = "Editing Rule #${mem.id}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            OutlinedTextField(
                                                value = editingText,
                                                onValueChange = { editingText = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(14.dp),
                                                minLines = 2,
                                                maxLines = 5
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                categoryLabels.forEach { (c, label) ->
                                                    FilterChip(
                                                        selected = editingCategory == c,
                                                        onClick = { editingCategory = c },
                                                        label = { Text(label) }
                                                    )
                                                }
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TextButton(onClick = { editingId = null }) {
                                                    Text("Cancel")
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        val clean = editingText.trim()
                                                        if (clean.isNotEmpty()) {
                                                            onUpdateMemory(mem.id, editingCategory, clean)
                                                            editingId = null
                                                        }
                                                    },
                                                    enabled = editingText.isNotBlank()
                                                ) {
                                                    Text("Save Changes")
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = mem.content,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(3.dp))
                                                Text(
                                                    text = "#${mem.id} · ${mem.sourceSummary}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    isCreatingNew = false
                                                    editingId = mem.id
                                                    editingText = mem.content
                                                    editingCategory = mem.category
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Edit,
                                                    contentDescription = "Edit rule",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(onClick = { onDeleteMemory(mem.id) }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.DeleteOutline,
                                                    contentDescription = "Delete rule",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamVaultSheet(
    spamRecords: List<NotificationRecordEntity>,
    onRescueItem: (NotificationRecordEntity) -> Unit,
    onPurgeAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filtered = remember(spamRecords, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            spamRecords
        } else {
            spamRecords.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.body.contains(q, ignoreCase = true) ||
                    it.appName.contains(q, ignoreCase = true) ||
                    it.sender.contains(q, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Disregarded Spam (${spamRecords.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (spamRecords.isNotEmpty()) {
                    TextButton(onClick = onPurgeAll) {
                        Text("Empty Spam")
                    }
                }
            }

            Text(
                text = "Silently archived items that never buzzed or cluttered your summary. Rescue any false positive in one tap to teach the Gatekeeper.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (spamRecords.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    placeholder = { Text("Search sender, app, or message...") }
                )
                Spacer(Modifier.height(12.dp))
            }

            if (filtered.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        text = if (spamRecords.isEmpty()) "Spam vault is empty." else "No disregarded items match '$searchQuery'.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        val relTime = DateUtils.getRelativeTimeSpanString(
                            item.postedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE
                        ).toString()

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${item.appName} · $relTime",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (item.body.isNotBlank()) {
                                    Text(
                                        text = item.body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "Why disregarded: ${item.reasoningRationale}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                AssistChip(
                                    onClick = { onRescueItem(item) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Restore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("Not spam · Rescue & Teach") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsTraceSheet(
    settings: AgentSettingsSnapshot,
    permissions: PermissionReadinessState,
    spamTotalCount: Int,
    recentTraces: List<TraceLogEntity>,
    onToggleGatekeeper: () -> Unit,
    onTogglePrivateMode: () -> Unit,
    onSetSleepStrictCalls: (Boolean) -> Unit,
    onSetTriageModel: (String) -> Unit,
    onSetTriageThinkingLevel: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onPasteApiKey: (String) -> Unit,
    onOpenSpamVault: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAuditTraces by rememberSaveable { mutableStateOf(false) }
    var showAllVoices by rememberSaveable { mutableStateOf(false) }

    val voicesToDisplay = remember(showAllVoices) {
        if (showAllVoices) GeminiVoicesClient.STUDIO_VOICES else GeminiVoicesClient.STUDIO_VOICES.take(8)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Controls & Audit",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Gatekeeper & Call Screen Exemption Section
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Default-Silent Gatekeeper",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Silences unvetted apps while exempting Phone Calls (Pixel Call Screen), Alarms & Media",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = settings.gatekeeperEnabled && permissions.dndPolicyGranted,
                                onCheckedChange = { onToggleGatekeeper() }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Overnight Starred Calls Only",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Restrict phone call ringers to Starred Contacts & repeat callers inside 15m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = settings.sleepStrictCalls,
                                onCheckedChange = onSetSleepStrictCalls
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (settings.privateModeActive) "Private Mode · Active" else "Private Mode",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Ask by voice/text or toggle here. Exiting permanently deletes everything from entry through exit.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = settings.privateModeActive,
                                onCheckedChange = { onTogglePrivateMode() }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Notification Triage Reasoning & Speed",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Real chain-of-thought reasoning verifies both sender identity and topic scope so sender-specific rules are never misapplied.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val triageOptions = listOf(
                                    "gemini-3.8-flash" to "3.8 Flash · Smart & Fast",
                                    "gemini-3.5-flash-lite" to "3.5 Flash-Lite · Light"
                                )
                                triageOptions.forEach { (modelId, label) ->
                                    val selected = settings.triageModel == modelId
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSetTriageModel(modelId) },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Text(
                                text = "Thinking Effort",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val thinkingOptions = listOf(
                                    "low" to "Low · Fast Thinking",
                                    "medium" to "Medium · Balanced",
                                    "high" to "High · Deepest"
                                )
                                thinkingOptions.forEach { (lvl, label) ->
                                    val selected = settings.triageThinkingLevel == lvl
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSetTriageThinkingLevel(lvl) },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Voice Persona Section
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Live Voice Persona · ${settings.voiceName}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Tip: You can ask the assistant in voice or text to look up available voices and switch to any tone on demand.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            voicesToDisplay.forEach { voice ->
                                val selected = voice.id.equals(settings.voiceName, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectVoice(voice.id) },
                                    leadingIcon = if (selected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    label = {
                                        val tag = voice.persona ?: voice.gender.orEmpty()
                                        Text("${voice.label} · $tag")
                                    }
                                )
                            }
                        }

                        TextButton(onClick = { showAllVoices = !showAllVoices }) {
                            Text(if (showAllVoices) "Show fewer voices" else "Show all ${GeminiVoicesClient.STUDIO_VOICES.size} Studio voices")
                        }
                    }
                }
            }

            // Deep Context & Spam Vault + API Key Rotation
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Deep Context & Storage",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = buildString {
                                append("Contacts: ${if (permissions.contactsGranted) "Ready" else "Off"} · ")
                                append("SMS/Calls: ${if (permissions.smsGranted && permissions.callLogGranted) "Ready" else "Off"} · ")
                                append("3rd-Party Chat Observer: ${if (permissions.accessibilityEnabled) "Active" else "Optional"}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AssistChip(
                                onClick = {
                                    onDismiss()
                                    onOpenSpamVault()
                                },
                                label = { Text("Spam Vault ($spamTotalCount)") }
                            )

                            if (!permissions.accessibilityEnabled) {
                                AssistChip(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    },
                                    label = { Text("Enable Chat Observer") }
                                )
                            }
                        }

                        AssistChip(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                                onPasteApiKey(clip)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(if (settings.hasApiKey) "Replace API Key from Clipboard" else "Paste API Key from Clipboard")
                            }
                        )
                    }
                }
            }

            // Epistemic Trace Audit Viewer
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAuditTraces = !showAuditTraces }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Epistemic Reasoning Audit (${recentTraces.size})",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Pre-decision rationale logs for triage, memory & tool calls",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (showAuditTraces) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null
                        )
                    }
                }
            }

            if (showAuditTraces) {
                items(recentTraces.take(40), key = { it.id }) { trace ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "[${trace.subsystem}] ${trace.eventType}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = trace.reasoningRationale,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = trace.decisionPayload,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!trace.errorStack.isNullOrBlank()) {
                                Text(
                                    text = trace.errorStack.take(260),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}
