# Pixel Agent — On-Device AI Notification Gatekeeper & Live Voice Assistant

A standalone Android application (`com.personal.pixelagent`) built for Pixel devices (`minSdk 34`, `targetSdk 35`) in Kotlin and Jetpack Compose Material 3. Pixel Agent acts as a default-silent notification gatekeeper, rationale-first triage engine, and synchronous voice/text personal assistant communicating directly with the Gemini API from the handset (`store = false`, zero intermediate backend).

---

## Key Capabilities

### 1. Default-Silent Notification Gatekeeper & Rationale-First Triage
- **OS-Level Silence with Call Screen Exemption**: Coordinates `NotificationListenerService` (`GatekeeperNotificationListener`) and `AutomaticZenRule` (`ZenModeController`) to hold unvetted app clutter silently while exempting Phone Calls (preserving Pixel Call Screen), Alarms, and Media—with an optional overnight Starred & Repeat-Caller mode.
- **Active Reasoning & Strict Conjunctive Scope**: Uses **`gemini-3.8-flash`** (`thinking_level = "low"` by default, user-configurable across `low` / `medium` / `high` or `gemini-3.5-flash-lite`) via the Gemini Interactions API (`/v1beta/interactions`). Every structured triage verdict generates `rationale` **before** committing to `tier` (`URGENT`, `SUMMARY`, or `SPAM`) and verifies all conditions of learned rules (exact sender, app/thread, and topic) conjunctively so sender-scoped rules are never misapplied across different contacts.
- **Parallel On-Device Relationship Context**: `CommunicationContextEngine` enriches triage in parallel using Contacts (starred status, relationship labels, notes), SMS/RCS engagement history, recent Call Logs, nearby Calendar events, and observed in-app chat context.
- **Fail-Fast & Explicit Fail-Open Safety**: Never conceals API/network errors or silently drops human messages—if triage fails or times out, direct messages pass through visibly and surface exact diagnostics in the Epistemic Reasoning Audit log.

### 2. Automatic Seen-Message Clearing & Active-Thread Suppression
- **Direct In-App Thread Detection (~250ms)**: `ActiveScreenContextService` and `SeenMessageTracker` detect when you open a conversation directly inside Google Messages (SMS & RCS) or any third-party chat app (requiring an active compose/reply field + matching thread header so viewing an inbox list never prematurely clears unread threads) and automatically dismiss the corresponding status-bar alert and Live Summary item.
- **Telephony Read-State Sync**: Observes `Telephony.MmsSms` / `Telephony.Sms` via `ContentObserver` so texts marked read from Google Messages for Web or a paired watch also clear automatically.
- **Zero Redundant Alerts on Open Threads**: Suppresses redundant heads-up alerts when a new reply arrives in a conversation thread you already have open on your unlocked screen.

### 3. Autonomous Rule & Semantic Memory Lifecycle
- **Full Agentic CRUD in Voice & Text**: Supports `list_memories`, `remember_preference_or_fact`, `update_memory`, and `delete_memory` across both Text Chat (`TextChatOrchestrator`) and `NON_BLOCKING` Live Voice (`LiveVoiceForegroundService`).
- **In-Place Rule Editing & Semantic Indexing**: Refines existing rules in place (`update_memory`) instead of creating conflicting duplicate rules, indexing 768-dimensional L2-normalized embeddings via `gemini-embedding-2`.
- **Transparent Receipts & 1-Tap Undo**: Every rule creation, edit, deletion, voice switch, or notification reclassification posts an inline receipt card with 1-tap **Undo**, alongside full manual create/edit/delete controls in the **Rules & Memories** sheet.

### 4. Synchronous Live Voice & Dynamic Voice Persona Catalog
- **Full-Duplex Audio (`gemini-3.8-live-extended-thinking`)**: Streams 16kHz PCM microphone input and 24kHz PCM audio output over `BidiGenerateContent` WebSockets (`GeminiLiveWebSocketClient` + `AudioDuplexController`) with real-time transcripts and barge-in interruption.
- **Anywhere Access**: Launchable from the 4x1 home-screen **Pixel Agent** widget (`AgentVoiceWidgetProvider`), the persistent Live Summary `Talk` action, or the morphing workspace composer (`VoiceOverlayActivity`).
- **Self-Configuring Voice Catalog**: Queries Gemini Studio voices (`GeminiVoicesClient`) via `search_available_voices` and hot-swaps active voice sessions on demand with `switch_agent_voice`.

### 5. Zero-Trace Private Mode (Voice & Text)
- **Natural-Language Entry & Exit**: Ask by voice or text to enter or exit Private Mode (`set_private_mode`), or toggle it from workspace controls, displaying an unmistakable purple Private Mode indicator across the workspace, floating voice overlay, notification, and widget.
- **Permanent Session Erasure**: Exiting Private Mode permanently deletes every SQLite conversation turn, temporary memory, and non-triage trace from the entry request through the exit request, runs `PRAGMA wal_checkpoint(TRUNCATE)`, and cleanly resets active Live Voice WebSocket context.

---

## Project Structure

```text
app/src/main/java/com/personal/pixelagent/
├── PixelAgentApp.kt                 # Dependency container & app lifecycle initialization
├── agent/
│   ├── AgentToolExecutor.kt         # Rationale-first tool schemas & execution (Text + NON_BLOCKING Live Voice)
│   ├── TextChatOrchestrator.kt      # Multi-turn Interactions API chat & live rule context injection
│   └── TriageContract.kt            # Structured JSON triage schema & conjunctive rule-scope contract
├── context/
│   ├── ActiveScreenContextService.kt# Accessibility observer for seen-message clearing & chat thread context
│   ├── CommunicationContextEngine.kt# Parallel Contacts, SMS, Call Log, Calendar & thread dossier builder
│   └── SeenMessageTracker.kt        # Auto-dismissal of seen threads, Telephony observer & active-thread suppression
├── data/
│   ├── local/                       # Room database (WAL), entities & DAO
│   ├── memory/                      # 768-d L2-normalized embedding math & semantic MemoryRepository
│   └── settings/                    # Android Keystore EncryptedSharedPreferences & ADB key provisioning receiver
├── gatekeeper/
│   ├── ActionIntentCache.kt         # PendingIntent & inline RemoteInput quick-reply cache
│   ├── BurstCoalescer.kt            # Per-thread notification burst coalescing
│   ├── GatekeeperNotificationListener.kt # Notification interception & structural OS bypass rules
│   ├── NotificationChannelsManager.kt    # Breakthrough alerts, silent Live Summary rollup & FGS notifications
│   ├── NotificationTriageEngine.kt  # End-to-end triage pipeline & explicit fail-open handling
│   └── ZenModeController.kt         # AutomaticZenRule DND policy with Call Screen exemption
├── network/
│   ├── GeminiEmbeddingClient.kt     # gemini-embedding-2 REST client (768-d)
│   ├── GeminiInteractionsClient.kt  # Gemini Interactions API client (/v1beta/interactions, store=false)
│   ├── GeminiLiveWebSocketClient.kt # BidiGenerateContent Live WebSocket client & voice/privacy reconnects
│   └── GeminiVoicesClient.kt        # Gemini Studio voice catalog lookup & validation
├── observability/
│   └── TraceLogger.kt               # Epistemic reasoning_rationale & stack-trace audit logger
├── ui/                              # Compose Material 3 workspace, VoiceOverlayActivity, Rules & Controls sheets
├── voice/                           # AudioDuplexController & LiveVoiceForegroundService
└── widget/                          # 4x1 home-screen AppWidget provider
```

---

## Prerequisites & Setup

1. **Toolchain**:
   - JDK 17 (`openjdk@17`)
   - Android SDK (`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`)
2. **Gemini API Key**:
   - Copy `.env.example` to `.env` (git-ignored) for automatic encrypted provisioning during deployment:
     ```bash
     cp .env.example .env
     # Set GEMINI_API_KEY=... inside .env
     ```
   - Or paste your key in 1 tap from the clipboard inside the app's **Setup** / **Controls** screen (stored in Android Keystore `AES256_GCM` `EncryptedSharedPreferences`).

---

## Build, Test & Deploy to Pixel

Run the JVM unit test suite:

```bash
./gradlew testDebugUnitTest
```

Build the debug APK, install onto a connected Pixel over USB or Wireless Debugging (`adb`), grant runtime permissions, enable the Notification Listener, DND rule access, and active thread observer, and launch the app in one command:

```bash
./scripts/deploy_and_grant_pixel.sh
```
