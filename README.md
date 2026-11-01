# JARVIS — Personal Android AI Voice Assistant (Bring-Your-Own-Key)

[![Android CI](https://github.com/sagarku03/Jarvis/actions/workflows/android.yml/badge.svg)](https://github.com/sagarku03/Jarvis/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/sagarku03/Jarvis?label=Latest%20Release&color=blueviolet)](https://github.com/sagarku03/Jarvis/releases/tag/v2.0.0)
[![Direct APK Download](https://img.shields.io/badge/Download-JARVIS%20v2.0%20APK-emerald?logo=android)](https://github.com/sagarku03/Jarvis/releases/download/v2.0.0/JARVIS-v2.0-Gemini3.8.apk)

JARVIS is a native Kotlin + Jetpack Compose Android voice assistant built with the **Aurora Violet Glass** design system (`#07050F` → `#130D2E` → `#1A1040`), an automated **Gemini 3.8 Dual-Model Architecture**, real-time half-duplex `VoiceSession` state machine (`IDLE → LISTENING → THINKING → SPEAKING`), customizable Gemini Live voice timbres (Male/Female), a 21-tool phone control registry with bilingual English & Hindi outputs, `SafetyGate` + emergency `KillSwitch`, and encrypted on-device Room persistence.

---

## ⚡ What's New in v2.0

- **Gemini 3.8 Dual-Model Strategy**:
  - **Voice Conversation Mode**: Automatically uses **`gemini-3.8-live`** when speaking via push-to-talk, hands-free voice call, or wake phrases.
  - **Text Typing Mode**: Automatically uses **`gemini-3.8-flash`** for instant text responses when typing in the chat drawer.
  - **Automated Fallback Chain**: Gracefully walks the fallback chain (`gemini-3.8-*` ➔ `gemini-2.0-flash` ➔ `gemini-2.5-flash`) if newer models are unavailable on an API key tier.
- **Gemini Live Voices & Gender Selection**:
  - **Male Voices**: `Puck` (Dynamic), `Charon` (Deep), `Fenrir` (Authoritative)
  - **Female Voices**: `Aoede` (Warm), `Kore` (Calm), `Leda` (Clear)
  - **In-App Voice Preview**: Test and listen to voice timbres directly in Settings.
- **100% Production-Ready Buttons & Audited Tools**:
  - All screens (Home, Voice Call, Memory, Routines, Permissions, Settings) have zero dummy buttons.
  - Complete bilingual output (English + Hindi) across all 21 system automation tools.
  - Fixed volume tool alias routing, hardware flashlight camera checks, Android 14 receiver registration flags, and Android 11+ `<queries>` package visibility.

---

## 🔑 Bring-Your-Own-Key (Zero Owner Cost, No Backend, No Login)

- **No login or cloud account required**: There is no Firebase Auth, no Cloud Run backend, and no Cloudflare Worker.
- **Direct Gemini API with your own key**: Each user enters their personal free Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) on first launch. The key is encrypted with AES-256-GCM backed by Android Keystore and stored exclusively in app-private storage.
- **On-device encrypted memory**: All conversations, learned facts, and activity logs are stored locally on the device with SQLCipher encryption.

---

## 📲 Direct APK Installation

Download the compiled, ready-to-install Android APK from the official release:
👉 **[Download JARVIS-v2.0-Gemini3.8.apk](https://github.com/sagarku03/Jarvis/releases/download/v2.0.0/JARVIS-v2.0-Gemini3.8.apk)**

---

## 🛠️ Build & Verify from Source

A clean clone requires no cloud keys, no `google-services.json`, and no external services:

```bash
# Assemble the Debug APK
./gradlew :app:assembleDebug

# Run unit and Robolectric tests
./gradlew :app:testDebugUnitTest

# Assemble the Release APK (requires keystore environment variables)
./gradlew :app:assembleRelease
```
