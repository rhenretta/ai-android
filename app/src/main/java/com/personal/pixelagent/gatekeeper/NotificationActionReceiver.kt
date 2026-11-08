package com.personal.pixelagent.gatekeeper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.voice.LiveVoiceForegroundService
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? PixelAgentApp ?: return
        when (intent?.action) {
            ACTION_CLEAR_DIGEST -> {
                val pendingResult = goAsync()
                app.applicationScope.launch {
                    try {
                        app.container.database.agentDao().clearActiveDigest()
                        app.container.channelsManager.refreshLiveSummaryNotification(
                            activeDigestItems = emptyList(),
                            gatekeeperEnabled = app.container.settingsStore.isGatekeeperEnabled(),
                            degradedError = app.container.settingsStore.state.value.lastDegradedError
                        )
                        AgentVoiceWidgetProvider.requestRefresh(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_STOP_VOICE -> {
                LiveVoiceForegroundService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_CLEAR_DIGEST = "com.personal.pixelagent.ACTION_CLEAR_DIGEST"
        const val ACTION_STOP_VOICE = "com.personal.pixelagent.ACTION_STOP_VOICE"
    }
}
