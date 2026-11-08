#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

APP_ID="com.personal.pixelagent"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
LISTENER_COMPONENT="$APP_ID/$APP_ID.gatekeeper.GatekeeperNotificationListener"
A11Y_COMPONENT="$APP_ID/$APP_ID.context.ActiveScreenContextService"

# 1. Resolve valid JDK 17 & Android SDK paths on macOS
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  if [[ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  else
    FOUND_JDK="$(find /opt/homebrew/Cellar/openjdk@17 -maxdepth 4 -name "Home" 2>/dev/null | head -n 1 || true)"
    if [[ -n "$FOUND_JDK" ]]; then
      export JAVA_HOME="$FOUND_JDK"
    fi
  fi
fi

if [[ -z "${ANDROID_HOME:-}" || ! -d "${ANDROID_HOME}" ]]; then
  if [[ -d "/opt/homebrew/share/android-commandlinetools" ]]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

ADB_BIN="${ANDROID_HOME:-}/platform-tools/adb"
if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi
if [[ -z "$ADB_BIN" || ! -x "$ADB_BIN" ]]; then
  echo "Error: 'adb' not found. Ensure Android platform-tools is installed." >&2
  exit 1
fi

# 2. Load GEMINI_API_KEY from .env if present (without printing the key)
if [[ -f "$PROJECT_ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_ROOT/.env"
  set +a
fi

echo "==> Building Pixel AI Gatekeeper debug APK..."
./gradlew assembleDebug

echo "==> Checking connected Pixel device..."
TARGET_SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [[ -z "$TARGET_SERIAL" ]]; then
  echo "Error: No authorized Android device detected over ADB." >&2
  echo "Connect your Pixel 7 Pro via USB or Wireless Debugging and re-run this script." >&2
  exit 1
fi
ADB=("$ADB_BIN" -s "$TARGET_SERIAL")
echo "   Using device: $TARGET_SERIAL"

echo "==> Installing $APK_PATH..."
"${ADB[@]}" install -r "$APK_PATH"

echo "==> Granting runtime permissions (Mic, Notifications, Contacts, SMS, Call Log, Calendar)..."
RUNTIME_PERMS=(
  "android.permission.POST_NOTIFICATIONS"
  "android.permission.RECORD_AUDIO"
  "android.permission.READ_CONTACTS"
  "android.permission.READ_SMS"
  "android.permission.READ_CALL_LOG"
  "android.permission.READ_CALENDAR"
)
for perm in "${RUNTIME_PERMS[@]}"; do
  "${ADB[@]}" shell pm grant "$APP_ID" "$perm" || echo "   Warning: could not auto-grant $perm"
done

echo "==> Enabling Notification Listener, DND Policy & Sensitive Notification Read Access..."
"${ADB[@]}" shell cmd notification allow_listener "$LISTENER_COMPONENT" || true
"${ADB[@]}" shell cmd notification allow_dnd "$APP_ID" || true
"${ADB[@]}" shell cmd appops set --user 0 "$APP_ID" RECEIVE_SENSITIVE_NOTIFICATIONS allow 2>/dev/null || true

echo "==> Enabling passive 3rd-party chat thread observer (AccessibilityService)..."
EXISTING_A11Y="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$EXISTING_A11Y" == "null" || -z "$EXISTING_A11Y" ]]; then
  NEW_A11Y="$A11Y_COMPONENT"
elif [[ "$EXISTING_A11Y" == *"$A11Y_COMPONENT"* ]]; then
  NEW_A11Y="$EXISTING_A11Y"
else
  NEW_A11Y="${EXISTING_A11Y}:${A11Y_COMPONENT}"
fi
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$NEW_A11Y" || true
"${ADB[@]}" shell settings put secure accessibility_enabled 1 || true

# 3. Zero-paste API key provisioning into Android Keystore EncryptedSharedPreferences
if [[ -n "${GEMINI_API_KEY:-}" && "${GEMINI_API_KEY}" != "your_gemini_api_key_here" ]]; then
  echo "==> Provisioning GEMINI_API_KEY into Pixel EncryptedSharedPreferences..."
  "${ADB[@]}" shell am broadcast \
    -a "com.personal.pixelagent.PROVISION_API_KEY" \
    -n "$APP_ID/.data.settings.AdbKeyProvisionReceiver" \
    --es api_key "$GEMINI_API_KEY" >/dev/null
  echo "   API key provisioned."
else
  echo "==> Note: GEMINI_API_KEY not set in .env or environment; you can paste it in 1 tap inside the app."
fi

echo "==> Launching Pixel AI Gatekeeper on your Pixel 7 Pro..."
"${ADB[@]}" shell am start -n "$APP_ID/.ui.MainActivity" >/dev/null

echo "==> All set! Long-press your Pixel home screen -> Widgets -> Pixel Agent to add the 4x1 Voice & Type pill."
