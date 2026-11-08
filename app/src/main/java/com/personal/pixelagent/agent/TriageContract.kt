package com.personal.pixelagent.agent

import com.personal.pixelagent.data.local.TriageTier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Mandatory Rationale-First Triage Contract.
 * `rationale` is strictly generated FIRST before committing to `tier` or copy tokens,
 * grounding autoregressive attention on relationship context, user memories, and temporal stakes.
 */
@Serializable
data class TriageVerdict(
    @SerialName("rationale") val rationale: String,
    @SerialName("tier") val tier: TriageTier,
    @SerialName("urgent_title") val urgentTitle: String? = null,
    @SerialName("urgent_body") val urgentBody: String? = null,
    @SerialName("summary_bullet") val summaryBullet: String? = null
)

object TriageContract {

    val JSON_SCHEMA: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "rationale",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive("Pre-decision epistemic deliberation generated BEFORE choosing tier: (1) identify the exact sender and relationship/engagement context, (2) check whether any learned rule applies ONLY by verifying that ALL of that rule's conditions (specific sender/person, app/group, and topic/timing) match this notification without transferring a sender-scoped rule onto a different person, and (3) justify the chosen tier.")
                        )
                    }
                )
                put(
                    "tier",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("URGENT"))
                                add(JsonPrimitive("SUMMARY"))
                                add(JsonPrimitive("SPAM"))
                            }
                        )
                        put(
                            "description",
                            JsonPrimitive("URGENT = interrupt user immediately; SUMMARY = hold silently in the live summary digest; SPAM = disregard into the spam vault.")
                        )
                    }
                )
                put(
                    "urgent_title",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Clear alert headline when tier is URGENT."))
                    }
                )
                put(
                    "urgent_body",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Clear, informative alert text when tier is URGENT."))
                    }
                )
                put(
                    "summary_bullet",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Crisp one-line digest summary (under 85 chars) when tier is SUMMARY."))
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("rationale"))
                add(JsonPrimitive("tier"))
            }
        )
    }

    const val BASELINE_SYSTEM_INSTRUCTION = """
You are the on-device Notification Gatekeeper for the user's personal Android phone.
Every notification has been held silently so you can decide whether it deserves to interrupt the user right now (`URGENT`), wait quietly in their single Live Summary digest (`SUMMARY`), or be disregarded to the Spam Vault (`SPAM`).

Follow this decision hierarchy:
1. **Learned User Rules & Strict Scope Matching**:
   - Standing directives, relationship notes, preferences, and active expectations explicitly taught by the user take highest priority over baseline defaults—**ONLY when ALL stated conditions of that rule match the incoming notification**.
   - Many user rules are conjunctive (combining a specific sender/person, conversation, or app WITH a specific topic, activity, or time window). Verify every condition before applying a rule.
   - **Never over-generalize a sender-scoped or thread-scoped rule onto a different person**: if a rule suppresses or demotes a topic from Person A (or Group A), do NOT apply that suppression when Person B messages about the same topic.
2. **Relationship & Reciprocal Engagement Signals**:
   - Carefully weigh the provided Relationship & Engagement Context (whether the sender is a saved/starred contact or close relation, whether the user actively messages or replies to this sender/thread in SMS or in-app chats, recent call history, and nearby calendar events).
3. **Default Baseline Policy (when no matching user rule overrides this specific sender & context)**:
   - `URGENT`: Direct 1:1 messages from real people the user knows or actively converses with; time-sensitive authentication/verification codes; critical security, financial fraud, or emergency alerts; imminent schedule or travel disruptions.
   - `SUMMARY`: Group conversations where the user is not urgently needed right now; routine order/shipping/statement updates; newsletters or informational updates from services the user uses; non-urgent social notifications.
   - `SPAM`: Unsolicited marketing promotions, coupon blasts, cold outreach, engagement bait, or repetitive ad notifications unrelated to an active user transaction.

Always generate `rationale` FIRST before selecting `tier`, explicitly checking who the sender is and whether every condition of any candidate user rule matches this exact sender and message. Keep `summary_bullet`, `urgent_title`, and `urgent_body` clear and concise.
"""

    fun buildTriagePrompt(
        localTimeFormatted: String,
        appName: String,
        packageName: String,
        category: String?,
        sender: String,
        title: String,
        body: String,
        messagingThreadLines: List<String>,
        communicationDossierText: String,
        memoryContextText: String
    ): String = buildString {
        appendLine("## Current Device Context")
        appendLine("- Local Time: $localTimeFormatted")
        appendLine()
        appendLine("## Learned User Memories & Standing Directives")
        appendLine(memoryContextText)
        appendLine()
        appendLine(communicationDossierText)
        appendLine()
        appendLine("## Incoming Notification Payload")
        appendLine("- App: $appName ($packageName)")
        appendLine("- OS Category: ${category ?: "unspecified"}")
        appendLine("- Sender / Conversation: ${sender.ifBlank { "(none)" }}")
        appendLine("- Title: $title")
        appendLine("- Body: $body")
        if (messagingThreadLines.isNotEmpty()) {
            appendLine("- Attached Notification Thread Messages (oldest to newest):")
            messagingThreadLines.forEach { line -> appendLine("    $line") }
        }
    }.trim()
}
