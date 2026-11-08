package com.personal.pixelagent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.R
import com.personal.pixelagent.gatekeeper.NotificationActionReceiver
import com.personal.pixelagent.gatekeeper.NotificationChannelsManager
import com.personal.pixelagent.ui.MainActivity
import com.personal.pixelagent.ui.VoiceOverlayActivity
import com.personal.pixelagent.voice.LiveVoiceForegroundService
import kotlinx.coroutines.launch

/**
 * 4x1 Home-Screen Material You Pill Widget:
 * - Left status zone: Calm summary state (taps into Main Workspace)
 * - Keyboard button: Opens Main Workspace with keyboard pre-focused (`EXTRA_FOCUS_KEYBOARD`)
 * - Mic / Stop button: Launches Translucent Floating Voice Sheet (or hangs up active voice in 1 tap)
 */
class AgentVoiceWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            val pending = goAsync()
            val app = context.applicationContext as? PixelAgentApp
            if (app == null) {
                pending.finish()
                return
            }
            app.applicationScope.launch {
                try {
                    updateAllWidgets(context, app)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        val app = context.applicationContext as? PixelAgentApp
        if (app == null) {
            pending.finish()
            return
        }
        app.applicationScope.launch {
            try {
                updateWidgetsById(context, app, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun updateAllWidgets(context: Context, app: PixelAgentApp) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, AgentVoiceWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            updateWidgetsById(context, app, mgr, ids)
        }
    }

    private suspend fun updateWidgetsById(
        context: Context,
        app: PixelAgentApp,
        mgr: AppWidgetManager,
        ids: IntArray
    ) {
        val settings = app.container.settingsStore.state.value
        val voiceState = LiveVoiceForegroundService.sessionState.value
        val digestCount = app.container.database.agentDao().getActiveDigestItems().size

        val statusLabel: String
        val dotDrawable: Int

        when {
            voiceState.isActive && (voiceState.isPrivateMode || settings.privateModeActive) -> {
                statusLabel = "Private · ${voiceState.activeVoiceName}"
                dotDrawable = R.drawable.widget_dot_private
            }
            voiceState.isActive -> {
                statusLabel = "Live · ${voiceState.activeVoiceName}"
                dotDrawable = R.drawable.widget_dot_active
            }
            settings.privateModeActive -> {
                statusLabel = "Private Mode"
                dotDrawable = R.drawable.widget_dot_private
            }
            !settings.hasApiKey -> {
                statusLabel = "Tap to finish setup"
                dotDrawable = R.drawable.widget_dot_paused
            }
            !settings.lastDegradedError.isNullOrBlank() -> {
                statusLabel = "Offline pass-through"
                dotDrawable = R.drawable.widget_dot_paused
            }
            !settings.gatekeeperEnabled -> {
                statusLabel = "Gatekeeper paused"
                dotDrawable = R.drawable.widget_dot_paused
            }
            digestCount > 0 -> {
                statusLabel = "Quiet · $digestCount in summary"
                dotDrawable = R.drawable.widget_dot_active
            }
            else -> {
                statusLabel = "All clear"
                dotDrawable = R.drawable.widget_dot_active
            }
        }

        val openWorkspaceIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openWorkspacePending = NotificationChannelsManager.createBackgroundAllowedActivityPendingIntent(
            context,
            201,
            openWorkspaceIntent
        )

        val focusKeyboardIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_FOCUS_KEYBOARD, true)
        }
        val focusKeyboardPending = NotificationChannelsManager.createBackgroundAllowedActivityPendingIntent(
            context,
            202,
            focusKeyboardIntent
        )

        val voiceActionPending: PendingIntent = if (voiceState.isActive) {
            val stopIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_STOP_VOICE
            }
            PendingIntent.getBroadcast(
                context,
                203,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val startVoiceSheetIntent = Intent(context, VoiceOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            NotificationChannelsManager.createBackgroundAllowedActivityPendingIntent(
                context,
                204,
                startVoiceSheetIntent
            )
        }

        for (widgetId in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_agent_voice)
            views.setTextViewText(R.id.widget_status_text, statusLabel)
            views.setImageViewResource(R.id.widget_status_dot, dotDrawable)
            views.setOnClickPendingIntent(R.id.widget_status_zone, openWorkspacePending)
            views.setOnClickPendingIntent(R.id.widget_btn_type, focusKeyboardPending)

            if (voiceState.isActive) {
                views.setInt(R.id.widget_btn_voice, "setBackgroundResource", R.drawable.widget_btn_end)
                views.setImageViewResource(R.id.widget_btn_voice, R.drawable.ic_stop)
                views.setInt(
                    R.id.widget_btn_voice,
                    "setColorFilter",
                    context.getColor(R.color.widget_btn_end_fg)
                )
            } else {
                views.setInt(R.id.widget_btn_voice, "setBackgroundResource", R.drawable.widget_btn_primary)
                views.setImageViewResource(R.id.widget_btn_voice, R.drawable.ic_mic)
                views.setInt(
                    R.id.widget_btn_voice,
                    "setColorFilter",
                    context.getColor(R.color.widget_btn_primary_fg)
                )
            }
            views.setOnClickPendingIntent(R.id.widget_btn_voice, voiceActionPending)

            mgr.updateAppWidget(widgetId, views)
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.personal.pixelagent.ACTION_WIDGET_REFRESH"

        fun requestRefresh(context: Context) {
            val intent = Intent(context, AgentVoiceWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }
}
