package com.personal.pixelagent.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ReportOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.pixelagent.data.local.NotificationRecordEntity
import com.personal.pixelagent.data.local.TriageTier

/**
 * Collapsible Held Summary Card:
 * - Occupies 0dp when both active digest items and today's spam count are zero.
 * - Automatically collapses to a 1-line bar while the soft keyboard is open so conversation stays unobstructed.
 * - Exposes transparent `Why?` reasoning rationale and 1-tap `Alert next time` / `Mark spam` teaching chips.
 */
@Composable
fun HeldSummaryCard(
    digestItems: List<NotificationRecordEntity>,
    spamCountToday: Int,
    isKeyboardOpen: Boolean,
    onOpenItem: (NotificationRecordEntity) -> Unit,
    onTeachItem: (NotificationRecordEntity, TriageTier, String) -> Unit,
    onClearAll: () -> Unit,
    onOpenSpamVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (digestItems.isEmpty() && spamCountToday == 0) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen) {
            expanded = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top summary header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = digestItems.isNotEmpty()) { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val headline = if (digestItems.isEmpty()) {
                    "Summary clear"
                } else {
                    val apps = digestItems.map { it.appName }.distinct().take(3).joinToString(", ")
                    "Held Summary (${digestItems.size}) · $apps"
                }

                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (digestItems.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClearAll) {
                            Text("Clear", style = MaterialTheme.typography.labelLarge)
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) "Collapse summary" else "Expand summary",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && digestItems.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    digestItems.take(8).forEachIndexed { index, record ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        DigestNotificationRow(
                            record = record,
                            onOpen = { onOpenItem(record) },
                            onPromoteUrgent = {
                                val who = record.sender.ifBlank { record.title }
                                onTeachItem(
                                    record,
                                    TriageTier.URGENT,
                                    "Treat notifications from '$who' in ${record.appName} similar to '${record.title}' as URGENT so they alert immediately."
                                )
                            },
                            onDemoteSpam = {
                                val who = record.sender.ifBlank { record.title }
                                onTeachItem(
                                    record,
                                    TriageTier.SPAM,
                                    "Disregard unsolicited or noisy notifications from '$who' in ${record.appName} similar to '${record.title}' as SPAM."
                                )
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (spamCountToday > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSpamVault() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$spamCountToday spam disregarded today",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Inspect ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun DigestNotificationRow(
    record: NotificationRecordEntity,
    onOpen: () -> Unit,
    onPromoteUrgent: () -> Unit,
    onDemoteSpam: () -> Unit
) {
    var showWhy by remember(record.id) { mutableStateOf(false) }

    val relativeTime = remember(record.postedAt) {
        DateUtils.getRelativeTimeSpanString(
            record.postedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${record.appName} · $relativeTime",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { showWhy = !showWhy }
            ) {
                Text(
                    text = if (showWhy) "Hide why" else "Why?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(3.dp))
        Text(
            text = record.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val summaryCopy = record.summaryLine?.takeIf { it.isNotBlank() } ?: record.body
        if (summaryCopy.isNotBlank()) {
            Text(
                text = summaryCopy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(visible = showWhy) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = record.reasoningRationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = onPromoteUrgent,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Alert next time") }
                        )
                        AssistChip(
                            onClick = onDemoteSpam,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.ReportOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Mark spam") }
                        )
                    }
                }
            }
        }
    }
}
