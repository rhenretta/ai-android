package com.personal.pixelagent.gatekeeper

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import com.personal.pixelagent.data.settings.SecureSettingsStore

/**
 * Manages the OS-level "Default-Silent Gatekeeper" AutomaticZenRule on the Pixel 7 Pro.
 * Exempts Phone Calls (preserving Pixel Call Screen), Alarms, and Media while silencing
 * raw third-party app interruptions and heads-up peeking so only AI-vetted URGENT alerts break through.
 */
class ZenModeController(
    private val context: Context,
    private val settingsStore: SecureSettingsStore
) {
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    fun hasPolicyAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    fun syncGatekeeperRule(
        enabled: Boolean = settingsStore.isGatekeeperEnabled(),
        sleepStrictCalls: Boolean = settingsStore.isSleepStrictCalls()
    ): Boolean {
        if (!hasPolicyAccess()) return false
        val effectiveActive = enabled && settingsStore.hasApiKey()

        val callPolicy = if (sleepStrictCalls) {
            ZenPolicy.PEOPLE_TYPE_STARRED
        } else {
            ZenPolicy.PEOPLE_TYPE_ANYONE
        }

        val zenPolicy = ZenPolicy.Builder()
            .allowCalls(callPolicy)
            .allowRepeatCallers(true)
            .allowAlarms(true)
            .allowMedia(true)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
            .allowReminders(false)
            .allowEvents(false)
            .allowSystem(false)
            .showPeeking(false)
            .build()

        val ownerComponent = ComponentName(context, GatekeeperNotificationListener::class.java)
        val configComponent = ComponentName(context.packageName, "${context.packageName}.ZenRuleConfigAlias")
        val conditionUri = Uri.parse("condition://${context.packageName}/gatekeeper_mode")

        val rule = AutomaticZenRule(
            RULE_DISPLAY_NAME,
            ownerComponent,
            configComponent,
            conditionUri,
            zenPolicy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )

        val existingRuleId = settingsStore.getZenRuleId()
        return try {
            val activeRuleId = if (existingRuleId != null && notificationManager.getAutomaticZenRule(existingRuleId) != null) {
                notificationManager.updateAutomaticZenRule(existingRuleId, rule)
                existingRuleId
            } else {
                val createdId = notificationManager.addAutomaticZenRule(rule)
                settingsStore.setZenRuleId(createdId)
                createdId
            }

            if (activeRuleId != null) {
                if (effectiveActive) {
                    // Pulse STATE_FALSE -> STATE_TRUE so Android 15+ Modes clears any prior manual override
                    notificationManager.setAutomaticZenRuleState(
                        activeRuleId,
                        Condition(conditionUri, "Standby", Condition.STATE_FALSE)
                    )
                }
                val condition = Condition(
                    conditionUri,
                    if (effectiveActive) "Holding quiet watch" else "Waiting for API key or paused",
                    if (effectiveActive) Condition.STATE_TRUE else Condition.STATE_FALSE
                )
                notificationManager.setAutomaticZenRuleState(activeRuleId, condition)
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val RULE_DISPLAY_NAME = "Pixel AI Gatekeeper"
    }
}
