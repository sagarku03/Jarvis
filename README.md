# JARVIS — Personal Android AI Voice Assistant

JARVIS is a native Kotlin + Jetpack Compose Android voice assistant with a half-duplex `VoiceSession` state machine (`IDLE → LISTENING → THINKING → SPEAKING`), neural cloud TTS with automatic high-quality offline TTS fallback, a 20-tool phone control registry, a strict `SafetyGate` + emergency `KillSwitch`, and two build modes (`FULL_CONTROL_MODE` and `STORE_SAFE_MODE`).

---

## Setup Keys

Never commit API keys or secrets to source control. Configure secrets via the **Secrets panel in Google AI Studio** (which populates `.env` / `BuildConfig` at build time):

| Key Name | Purpose | Fallback when unconfigured |
| --- | --- | --- |
| `JARVIS_BACKEND_BASE_URL` | Base URL for your Cloud Run `/v1/agent/step`, `/v1/stt`, and `/v1/tts` proxy | Uses local offline tool routing (`FakeBackend` in debug builds) |
| `JARVIS_BACKEND_CERT_SHA256` | Optional `sha256/...` TLS certificate pin for the backend host | Standard Android HTTPS system trust store |
| `GOOGLE_WEB_CLIENT_ID` | Web OAuth 2.0 Client ID for Jetpack Credential Manager Google Sign-In | Shows `"GOOGLE_WEB_CLIENT_ID is not configured in Secrets"` |
| `PORCUPINE_ACCESS_KEY` | Picovoice Porcupine AccessKey for on-device `"Hey Jarvis"` wake word (requires `app/src/main/assets/hey_jarvis_android.ppn`) | Shows `"Wake word not set up"` badge in Settings and disables the toggle |
| `STORE_SAFE_MODE` | Set to `true` for Play Store compliant builds (disables `JarvisAccessibilityService` UI automation tools) | Defaults to `false` (`FULL_CONTROL_MODE = true`) |

---

## Architecture Overview

1. **`app/ui`**: Jetpack Compose screens (`HomeScreen`, `ActivityLogScreen`, `MemoryScreen`, `RoutinesScreen`, `SettingsScreen`, `PermissionsScreen`, `LoginScreen`), `JarvisOrb`, and `ViewModel`s.
2. **`app/services/voice`**:
   - `VoiceSessionManager`: Single owner of the microphone and speaker enforcing half-duplex audio (`IDLE → LISTENING → THINKING → SPEAKING`), a 500 ms post-TTS speaker tail delay, barge-in on orb tap, a ≥70% self-echo similarity filter, and a TTS text sanitizer (`sanitizeTextForTts`).
   - `AndroidSpeechRecognizerWrapper`: Single reusable `SpeechRecognizer` instance emitting live `rmsDbLevel` for the orb and handling all `SpeechRecognizer.ERROR_*` codes.
   - `AndroidTtsEngineWrapper`: Sentence-chunked neural TTS via `/v1/tts` with disk caching for short phrases and automatic offline `en-IN` / `en-US` high-quality voice fallback (`Offline voice` chip).
   - `PorcupineWakeWordEngineImpl`: On-device wake-word monitoring active only while `VoiceSession` is `IDLE`.
3. **`app/domain`**: `AgentOrchestrator`, `SafetyGate`, `KillSwitch`, `ContactsResolver`, and `ToolRegistry`.
4. **`app/tools`**: 20 tools across intents, system controls, notifications, and `AccessibilityService` screen automation.
5. **`backend-proxy/`**: Node.js + TypeScript Cloud Run proxy verifying Firebase Auth ID tokens and Play Integrity App Check headers.

---

## 5-Step Real-Phone Manual Test Script

1. **Half-Duplex Voice & Zero Echo Loop**:
   - Tap the central orb on the Home screen and say `"Turn on the flashlight"` or `"Set an alarm for 7 AM"`.
   - Verify the state caption transitions cleanly (`Listening…` → `Thinking…` → `Speaking…` → `Tap orb to talk`), the assistant speaks its reply once without re-triggering the microphone on its own voice, and tapping the orb while `Speaking…` immediately interrupts playback (barge-in) and starts listening.
2. **Voice Selection, Speed & Offline Fallback Chip**:
   - Open **Settings → Assistant Voice**, pick one of the 4 neural voices (`en-IN-Neural2-D`, `en-IN-Neural2-A`, `en-US-Studio-O`, `en-GB-Neural2-B`), adjust the **Speech Speed** slider (`0.8x–1.2x`), and tap **Preview Voice**.
   - Toggle Airplane Mode (or test without a backend URL) and verify the **Offline voice** chip appears in the header and device TTS speaks clearly.
3. **High-Risk SafetyGate Confirmation & Contact Disambiguation**:
   - Save a contact in **Memory** (e.g., Key: `Mom`, Value: `+91 98765 43210`), then say `"Call Mom"` or `"Send WhatsApp to Mom saying I will reach in 10 minutes"`.
   - Verify the **Confirm HIGH Risk Action** modal appears with the 15-second auto-cancel countdown, masked phone number, and **Approve & Execute** / **Deny** buttons, and that non-idempotent tools are never retried automatically.
4. **Full Control Accessibility & Banking/Password Blocklist**:
   - Enable **Jarvis Accessibility Service** in **Settings → Permissions**, then say `"Read screen"`.
   - Open any password field or UPI/banking app (such as Google Pay, PhonePe, Paytm) and verify `JarvisAccessibilityService` immediately blocks screen reading and gesture execution.
5. **Emergency KillSwitch (UI, Notification & Hardware Volume Combo)**:
   - Trigger the **Kill Switch** from the Home top bar, the foreground notification **STOP** action, the floating overlay **STOP** pill, or by double-pressing **Volume Down**.
   - Verify all microphone capture, TTS playback, and active agent tool loops stop immediately until **Resume Assistant** is tapped.
