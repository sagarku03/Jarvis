# JARVIS — Personal Android AI Voice Assistant (Free Mode + Aurora Violet Glass UI)

JARVIS is a native Kotlin + Jetpack Compose Android voice assistant built with the **Aurora Violet Glass** design system (`#07050F` → `#130D2E` → `#1A1040`), a strict half-duplex `VoiceSession` state machine (`IDLE → LISTENING → THINKING → SPEAKING`), high-quality offline Android TTS (`en-IN` / `en-US` / `en-GB`), a 20-tool phone control registry, `SafetyGate` + emergency `KillSwitch`, AES-256-GCM encrypted Room storage, and a zero-cost **Cloudflare Workers** backend proxy.

---

## Free Setup Guide ($0 / No Credit Card Required)

### 1. Get a Free Gemini API Key (Google AI Studio)
1. Open [Google AI Studio](https://aistudio.google.com/apikey) and create a free API key for `gemini-2.5-flash`.
2. Keep the key private—it is only stored in your Cloudflare Worker secret store, never in the Android APK.

### 2. Deploy the Free Cloudflare Worker Backend (`backend-proxy/`)
The backend runs on the **Cloudflare Workers Free Plan** (100,000 requests/day + Workers KV for per-user daily quota).

```bash
# 1. Authenticate Wrangler with your free Cloudflare account
npx wrangler login

# 2. Create a free Workers KV namespace for daily per-user quota tracking
npx wrangler kv namespace create QUOTA_KV --config backend-proxy/wrangler.toml
# Copy the returned KV namespace id into backend-proxy/wrangler.toml

# 3. Set your Firebase project ID in backend-proxy/wrangler.toml:
# FIREBASE_PROJECT_ID = "your-firebase-project-id"

# 4. Upload your free Gemini API key as an encrypted Worker secret
npx wrangler secret put GEMINI_API_KEY --config backend-proxy/wrangler.toml

# 5. Deploy the Worker
npx wrangler deploy --config backend-proxy/wrangler.toml
```

Verify the Worker health check:
```bash
curl https://jarvis-backend-proxy.<your-subdomain>.workers.dev/healthz
```

### 3. Configure Android Secrets (`.env` / AI Studio Secrets Panel)
Never commit secrets to git. Set these keys in the **Secrets panel in Google AI Studio** (or local `.env` copied from `.env.example`):

| Key Name | Purpose | Behavior when not configured |
| --- | --- | --- |
| `JARVIS_BACKEND_BASE_URL` | Your deployed `https://<worker>.workers.dev/` URL | Uses offline local commands; debug builds fall back to `FakeBackend`, release builds fail closed |
| `GOOGLE_WEB_CLIENT_ID` | Web OAuth 2.0 Client ID from Firebase Auth (Spark Free Plan) | Shows `"GOOGLE_WEB_CLIENT_ID is not configured"` on the login card |
| `AI_VOICE` | Optional `true` / `false` toggle for `/v1/tts` cloud synthesis | Defaults to `false` (uses free on-device high-quality Android TTS) |
| `STORE_SAFE_MODE` | Set `true` to disable `JarvisAccessibilityService` UI automation tools | Defaults to `false` (`FULL_CONTROL_MODE = true`) |

### 4. Build & Test Without Any Cloud Keys
A clean clone builds and passes all unit tests out of the box with zero cloud keys or `google-services.json`:

```bash
# Run Android JVM & Robolectric tests
gradle :app:testDebugUnitTest

# Run Cloudflare Worker unit & contract tests
node --experimental-strip-types --test backend-proxy/test/backend.test.ts

# Build the Debug APK
gradle :app:assembleDebug
```

---

## Architecture Overview

1. **`app/ui` (Aurora Violet Glass UI)**:
   - Deep space-violet radial/vertical backgrounds (`#07050F` → `#130D2E`), frosted glass cards (`AuroraGlassCard`), floating pill navigation (`AuroraFloatingPillNav`), glass login card, glass safety confirmation sheet (`AuroraGlassSafetySheet`), and red `STOP` pill (`#FF6B87`).
2. **`app/services/voice` (Free On-Device Voice Engine)**:
   - `VoiceSessionManager`: Enforces half-duplex audio (`IDLE → LISTENING → THINKING → SPEAKING`), 500 ms post-TTS speaker tail delay, barge-in on orb tap, ≥70% self-echo similarity filter, and TTS text sanitization.
   - `AndroidTtsEngineWrapper`: Selects high-quality (`QUALITY_VERY_HIGH` / `QUALITY_HIGH`) offline `en-IN` / `en-US` / `en-GB` voices by default and provides an `"Install better voice"` shortcut to Android `TTS_SETTINGS`.
   - `FreeWakeWordEngineImpl`: Zero-cost wake-word implementation that reports `"Not available in Free Mode — use the floating orb or notification button"`.
3. **`app/data/local` (Encrypted Room Persistence)**:
   - `FieldCipher`: AES-256-GCM field-level encryption backed by `AndroidKeyStore` for `chat_messages.content`, `memory_facts.value`, and `activity_logs.argumentsSummary`. Fails closed if ciphertext is tampered with.
   - `JarvisDatabase`: Versioned Room schema (`exportSchema = true`) with explicit `MIGRATION_1_2` and `MIGRATION_2_3` migrations.
4. **`backend-proxy/` (Cloudflare Worker in TypeScript)**:
   - Verifies Firebase Auth RS256 JWTs via Web Crypto API against Google's public JWKS keys.
   - Enforces a 200 requests/day per-user quota in Workers KV (returns HTTP 429 `DAILY_FREE_LIMIT_REACHED`).
   - Calls `gemini-2.5-flash` with full `functionCall` / `functionResponse` (`role: "function"`) tool-calling support.
