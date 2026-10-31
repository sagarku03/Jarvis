# JARVIS — Personal Android AI Voice Assistant (Bring-Your-Own-Key)

JARVIS is a native Kotlin + Jetpack Compose Android voice assistant built with the **Aurora Violet Glass** design system (`#07050F` → `#130D2E` → `#1A1040`), a strict half-duplex `VoiceSession` state machine (`IDLE → LISTENING → THINKING → SPEAKING`), on-device Android TTS (`en-IN` / `en-US` / `en-GB`), a 20-tool phone control registry, `SafetyGate` + emergency `KillSwitch`, and encrypted on-device Room persistence.

---

## Bring-Your-Own-Key (Zero Owner Cost, No Backend, No Login)

- **No login or cloud account required**: There is no Firebase Auth, no Cloud Run backend, and no Cloudflare Worker.
- **Direct Gemini API with your own key**: Each user enters their personal free Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) on first launch. The key is encrypted with AES-256-GCM backed by Android Keystore and stored exclusively in app-private storage.
- **On-device encrypted memory**: All conversations, learned facts, and activity logs are stored locally on the device.

---

## Build & Verify from a Clean Clone

A clean clone requires no cloud keys, no `google-services.json`, and no external services:

```bash
# Run unit and Robolectric tests
./gradlew :app:testDebugUnitTest

# Assemble the Debug APK
./gradlew :app:assembleDebug

# Assemble the Release APK
./gradlew :app:assembleRelease
```
