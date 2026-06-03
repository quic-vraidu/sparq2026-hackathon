# Autonomous on-device AI Agent for Android

An autonomous AI agent that runs **Qwen 3 4B / Llama 3 3B** on the Qualcomm Hexagon HTP NPU via the **Genie SDK** to execute multi-step phone tasks — SMS, calls, screen control, files, camera, and more — from a single natural language command, with no cloud required.

---

## Requirements

### Development Environment
| Tool | Version |
|------|---------|
| Android Studio | Meerkat \| 2024.3.1 |
| Android Gradle Plugin | 8.x (via libs.versions.toml) |
| Kotlin | 2.0.21 |
| CMake | 3.22.1 |
| NDK | r27 or later (arm64-v8a) |
| Java | 17 |
| QAIRT | 2.42|

### Device Requirements
| Requirement | Minimum |
|-------------|---------|
| Android SDK | 33 (Android 13) |
| Architecture | arm64-v8a |
| RAM | 8 GB recommended (6 GB minimum) |
| Chipset | Snapdragon 8 series (for Genie HTP NPU) |
| Storage | 10 GB free (for model files) |

### Qualcomm SDK Prerequisites (Genie backend — Phase 1)
These files are **not in the repo** and must be obtained from Qualcomm AI Hub:

```
app/src/main/cpp/genie/GenieDialog.h
app/src/main/cpp/genie/GenieCommon.h
app/src/main/jniLibs/arm64-v8a/libGenie.so
app/src/main/jniLibs/arm64-v8a/libQnnHtp.so
app/src/main/jniLibs/arm64-v8a/libQnnHtpV81Stub.so
```

> `libcdsprpc.so` and `libQnnHtpV81Skel.so` are **excluded from the APK** — they are loaded from the vendor namespace (`/vendor/lib64/`) pre-installed on Snapdragon 8 devices.

---

## Model Setup

### Genie (Qwen 3 4B / Llama 3 3B on HTP NPU) — Recommended
1. Obtain the quantised model package from Qualcomm AI Hub (QNN `.bin` + `genie_config.json`)
2. Push the model directory to the device:
   ```bash
   adb push <model_dir>/ /sdcard/Android/data/com.aster.ondevice/files/models/
   ```
   Model Folder: "\\chandrayaan\cameracustomerissues\venu\sparq26_hackathon_models"
   
4. In the app → **Settings** → set **Genie Config Path** to the absolute path of `genie_config.json`
5. Tap **Load Model**

### QAIC Cloud (gpt-oss-20b) — Validation / Fallback
1. In the app → **Settings** → select backend **QAIC Cloud**
2. Enter your **QAIC API Key** (`qaic_...`)
3. Enter your **Apigee Token**
4. Model ID: `gpt-oss-20b` (default)
5. Base URL: `https://dev.apigwx-op.qualcomm.com/aips/sparq/api`
6. Tap **Save** then **Load Model**

---

## Build Instructions

### 1. Clone and open
```bash
git clone <repo-url>
cd sparq2026-hackathon

```
Open in **Android Studio Meerkat | 2024.3.1**.

### 2. Place Genie SDK files
Copy headers and `.so` libraries to the paths listed under [Qualcomm SDK Prerequisites](#qualcomm-sdk-prerequisites-genie-backend--phase-1).

### 3. Build debug APK
```bash
./gradlew assembleDebug
```

### 4. Clean build (required after changing CMakeLists or .cpp files)
```bash
./gradlew clean assembleDebug
```

### 5. Install on device
```bash
./gradlew installDebug
# or
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First-Time Device Setup

### 1. Grant permissions
Open the app and grant all requested permissions:
- SMS (read, send, receive)
- Location (fine + coarse)
- Camera and Microphone
- Storage / Media
- Notifications
- Phone (call)
- Contacts

### 2. Enable Accessibility Service
```
Settings → Accessibility → Installed Services → Aster → Enable
```
Required for screen control tools (`take_screenshot`, `get_screen_hierarchy`, `input_gesture`, `click_by_text`, etc.)

### 3. Enable Notification Access
```
Settings → Notification Access → Aster → Allow
```
Required for `read_notifications` and `dismiss_notification` tools.

### 4. Declare vendor native libraries (automatic via manifest)
The manifest declares `libcdsprpc.so` as a `uses-native-library`. No manual step required — Android loads it from the vendor namespace automatically.

---

## Usage

### Chat (text)
Open the **Chat** tab, type a command:
```
"Set an alarm for next 5 min"
"Post a test notification"
"Clear all notifications"

```

### Voice
Open the **Voice** tab, tap the mic, speak your command. The agent executes the task and speaks the response via TTS.

### SMS Analyser
Open the **Analyser** tab:
1. Select year and month
2. Tap **Run** under "Monthly Spending Total"
3. The agent reads your SMS in batches, extracts transaction amounts using the on-device LLM, and returns your total monthly spending in ₹

### ADB Logs
```bash
# Watch agent + engine logs
adb logcat -s OnDeviceAgent GenieEngine LiteRtEngine RoutingLlmEngine ToolDispatcher

# Watch all app logs
adb logcat --pid=$(adb shell pidof com.aster.ondevice)
```

Agent logs (full prompt + output per step) are written to:
```
/storage/emulated/0/Download/aster/aster_YYYYMMDD_HHmmss.txt
```
Analyser logs:
```
/storage/emulated/0/Download/aster/analyser_<source>_YYYYMMDD_HHmmss.txt
```

---

## Architecture

```
Trigger Layer
  ChatScreen ──┐
  VoiceScreen ─┼──► OnDeviceAgent (ReAct loop, max 10 steps)
  SMS Receiver ┘         │
                         ▼
              SystemPromptBuilder (Llama3/Qwen3 or OpenAI template)
                         │
                    LLM generate()  ←── RoutingLlmEngine
                         │                   ├── GenieEngine   (Genie SDK → HTP NPU)
                    JSON repair              └── QaicEngine    (QAIC REST API → gpt-oss-20b)
                    ToolDispatcher
                         │
        ┌────────────────┼────────────────┐
   SmsHandler    AccessibilityHandler   FileSystemHandler  ...17 handlers, 55 tools
```

---

## Key Configuration (build.gradle.kts)
- **compileSdk / targetSdk**: 35
- **minSdk**: 33
- **ABI**: arm64-v8a only
- **C++**: `-std=c++17 -O3 -DNDEBUG`
- **HTTP**: OkHttp (QAIC cloud calls)
- `jniLibs.useLegacyPackaging = true` — extracts `.so` to disk (required for ADSP RPC)
- `libcdsprpc.so` and `libQnnHtpV81Skel.so` excluded from APK (loaded from vendor namespace)

---

## Validated Models

| Model | Backend | Template | Best For |
|-------|---------|----------|----------|
| Qwen 3 4B | Genie SDK (HTP NPU) | ChatML + /no_think | Tool calling, multi-step tasks |
| Llama 3 3B | Genie SDK (HTP NPU) | Llama3 | SMS analysis, general chat |
| gpt-oss-20b | QAIC Cloud | OpenAI | Validation, complex reasoning |
