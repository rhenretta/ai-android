package com.personal.pixelagent.data.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.personal.pixelagent.BuildConfig
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.widget.AgentVoiceWidgetProvider

/**
 * Zero-paste ADB provisioning receiver invoked by scripts/deploy_and_grant_pixel.sh:
 *   adb shell am broadcast -a com.personal.pixelagent.PROVISION_API_KEY -n com.personal.pixelagent/.data.settings.AdbKeyProvisionReceiver --es api_key "..."
 * Restricted to debug builds or shell/system callers so third-party untrusted apps cannot overwrite keys.
 */
class AdbKeyProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PROVISION_API_KEY) return
        if (!BuildConfig.DEBUG && callingUidIsUntrusted()) {
            Log.w("PixelAgent/Provision", "Rejected key provision broadcast in non-debug build.")
            return
        }

        val apiKey = intent.getStringExtra(EXTRA_API_KEY)?.trim()
        if (apiKey.isNullOrEmpty()) {
            Log.w("PixelAgent/Provision", "PROVISION_API_KEY received with empty api_key extra.")
            return
        }

        val app = context.applicationContext as PixelAgentApp
        app.container.settingsStore.saveApiKey(apiKey)
        app.container.zenModeController.syncGatekeeperRule()
        AgentVoiceWidgetProvider.requestRefresh(context)
        Log.i("PixelAgent/Provision", "Gemini API key provisioned into Android Keystore successfully.")
    }

    private fun callingUidIsUntrusted(): Boolean {
        val uid = Process.myUid()
        return uid > Process.FIRST_APPLICATION_UID
    }

    companion object {
        const val ACTION_PROVISION_API_KEY = "com.personal.pixelagent.PROVISION_API_KEY"
        const val EXTRA_API_KEY = "api_key"
    }
}
