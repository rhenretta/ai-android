package com.personal.pixelagent.context

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.pixelagent.PixelAgentApp
import com.personal.pixelagent.data.local.ScreenThreadSnapshotEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Passively observes active third-party app windows (e.g., Google Messages, WhatsApp, Signal, Slack):
 * 1. Immediately detects (~250ms) when the user opens a conversation thread directly and clears
 *    any matching held or breakthrough notifications via `SeenMessageTracker`.
 * 2. Indexes visible non-password thread snippets into local `ScreenThreadSnapshotEntity` rows
 *    (outside Private Mode) so future notification triage knows which conversations are active.
 */
class ActiveScreenContextService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastSeenChecks = ConcurrentHashMap<String, Job>()
    private val pendingSnapshots = ConcurrentHashMap<String, Job>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val app = applicationContext as? PixelAgentApp ?: return

        // Update foreground app tracking on genuine window switches (ignore background status-bar clock ticks)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            app.container.seenMessageTracker.onWindowPackageSwitched(pkg)
        }

        if (pkg in IGNORED_SYSTEM_PACKAGES || pkg == applicationContext.packageName) return

        // 1. Fast ~250ms throttled check to auto-dismiss seen notifications without animation starvation
        val existingFast = fastSeenChecks[pkg]
        if (existingFast == null || !existingFast.isActive) {
            fastSeenChecks[pkg] = serviceScope.launch {
                delay(250L)
                inspectWindowForSeenMessages(app, pkg)
            }
        }

        // 2. Debounced 1.2s thread context snapshot for future triage (disabled during Private Mode)
        if (!app.container.settingsStore.isPrivateModeActive()) {
            pendingSnapshots[pkg]?.cancel()
            pendingSnapshots[pkg] = serviceScope.launch {
                delay(1_200L)
                captureActiveWindowSnapshot(app, pkg)
            }
        }
    }

    private suspend fun inspectWindowForSeenMessages(app: PixelAgentApp, expectedPkg: String) {
        val parsed = extractActiveWindowData(expectedPkg) ?: return
        app.container.seenMessageTracker.onActiveWindowInspected(
            VisibleWindowSnapshot(
                packageName = parsed.packageName,
                topHeaderTexts = parsed.topHeaderTexts,
                allVisibleTexts = parsed.distinctLines,
                hasComposerInput = parsed.hasComposerInput
            )
        )
    }

    private suspend fun captureActiveWindowSnapshot(app: PixelAgentApp, expectedPkg: String) {
        if (app.container.settingsStore.isPrivateModeActive()) return
        val parsed = extractActiveWindowData(expectedPkg) ?: return
        if (parsed.distinctLines.size < 2) return

        val headerTitle = parsed.topHeaderTexts.firstOrNull()?.take(80)
            ?: parsed.distinctLines.firstOrNull()?.take(80)
            ?: parsed.packageName
        val snippet = parsed.distinctLines.drop(1).takeLast(14).joinToString(" | ").take(900)
        if (snippet.isBlank()) return

        val appLabel = try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(parsed.packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            parsed.packageName
        }

        if (app.container.settingsStore.isPrivateModeActive()) return
        val threadKey = "${parsed.packageName}|${headerTitle.lowercase()}"
        app.container.database.agentDao().upsertScreenThreadSnapshot(
            ScreenThreadSnapshotEntity(
                threadKey = threadKey,
                packageName = parsed.packageName,
                appLabel = appLabel,
                headerTitle = headerTitle,
                transcriptSnippet = snippet,
                capturedAt = System.currentTimeMillis()
            )
        )
    }

    private fun extractActiveWindowData(expectedPkg: String): ParsedWindowData? {
        val root = rootInActiveWindow ?: return null
        val actualPkg = root.packageName?.toString() ?: expectedPkg
        if (actualPkg in IGNORED_SYSTEM_PACKAGES || actualPkg == applicationContext.packageName) return null

        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val headerCutoffY = (screenHeight * 0.22f).toInt()

        val collectedNodes = ArrayList<PositionedText>(64)
        var composerFound = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || node.isPassword) return

            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (!composerFound && bounds.centerY() > headerCutoffY) {
                    val isEditableField = node.isEditable ||
                        node.className?.toString() == "android.widget.EditText" ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    if (isEditableField) {
                        composerFound = true
                    }
                }

                if (collectedNodes.size < 96) {
                    val txt = node.text?.toString()?.trim()
                    if (!txt.isNullOrBlank()) {
                        collectedNodes.add(PositionedText(txt, bounds.top, bounds.left))
                    }
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        if (collectedNodes.isEmpty() && !composerFound) return null

        collectedNodes.sortWith(compareBy<PositionedText>({ it.top }, { it.left }))

        val topHeaders = collectedNodes
            .filter { it.top <= headerCutoffY && it.text.length >= 2 }
            .map { it.text }
            .distinct()

        val distinctLines = collectedNodes
            .map { it.text }
            .distinct()

        return ParsedWindowData(
            packageName = actualPkg,
            topHeaderTexts = topHeaders,
            distinctLines = distinctLines,
            hasComposerInput = composerFound
        )
    }

    override fun onInterrupt() {
        // No-op: passive observer only
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private data class PositionedText(
        val text: String,
        val top: Int,
        val left: Int
    )

    private data class ParsedWindowData(
        val packageName: String,
        val topHeaderTexts: List<String>,
        val distinctLines: List<String>,
        val hasComposerInput: Boolean
    )

    companion object {
        private val IGNORED_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.google.android.inputmethod.latin",
            "com.google.android.permissioncontroller"
        )

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.contains("${context.packageName}/${ActiveScreenContextService::class.java.name}") ||
                enabled.contains("${context.packageName}/.context.ActiveScreenContextService")
        }
    }
}
