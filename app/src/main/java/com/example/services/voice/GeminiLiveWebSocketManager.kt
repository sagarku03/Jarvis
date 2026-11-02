package com.example.services.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.example.data.local.ApiKeyStore
import com.example.data.local.DiagnosticsLogger
import com.example.di.Inject
import com.example.di.Singleton
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed class LiveSessionState {
    object Disconnected : LiveSessionState()
    object Connecting : LiveSessionState()
    data class Connected(val sessionVoiceName: String) : LiveSessionState()
    data class Failed(val reason: String, val canFallbackToCascade: Boolean = true) : LiveSessionState()
}

@Singleton
class GeminiLiveWebSocketManager @Inject constructor(
    private val context: Context,
    private val apiKeyStore: ApiKeyStore,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi = Moshi.Builder().build()
) {
    companion object {
        const val LIVE_MODEL = "gemini-3.8-live"
        private const val LIVE_WS_BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapAdapter = moshi.adapter(Map::class.java)

    private val _sessionState = MutableStateFlow<LiveSessionState>(LiveSessionState.Disconnected)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private var activeWebSocket: WebSocket? = null
    private var activeAudioTrack: AudioTrack? = null

    private val liveClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for persistent live stream
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun connect(voiceName: String = "Puck", onFallbackToCascade: ((String) -> Unit)? = null) {
        val apiKey = apiKeyStore.get()?.trim()
        if (apiKey.isNullOrEmpty()) {
            _sessionState.value = LiveSessionState.Failed("No API key configured", canFallbackToCascade = true)
            onFallbackToCascade?.invoke("No API key configured")
            return
        }

        disconnect()
        _sessionState.value = LiveSessionState.Connecting
        DiagnosticsLogger.log("LiveWebSocket", "Connecting to Gemini Live WebSocket with voice: $voiceName")

        val wsUrl = "$LIVE_WS_BASE?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = liveClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DiagnosticsLogger.log("LiveWebSocket", "WebSocket connection opened: HTTP ${response.code}")
                // Send initial setup frame
                val setupFrame = mapOf(
                    "setup" to mapOf(
                        "model" to "models/$LIVE_MODEL",
                        "generationConfig" to mapOf(
                            "responseModalities" to listOf("AUDIO"),
                            "speechConfig" to mapOf(
                                "voiceConfig" to mapOf(
                                    "prebuiltVoiceConfig" to mapOf("voiceName" to voiceName)
                                )
                            )
                        )
                    )
                )
                val json = mapAdapter.toJson(setupFrame)
                webSocket.send(json)
                _sessionState.value = LiveSessionState.Connected(voiceName)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val err = "WebSocket failed: ${t.localizedMessage ?: "Connection dropped"} (HTTP ${response?.code ?: 0})"
                DiagnosticsLogger.log("LiveWebSocket", err)
                _sessionState.value = LiveSessionState.Failed(err, canFallbackToCascade = true)
                onFallbackToCascade?.invoke(err)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DiagnosticsLogger.log("LiveWebSocket", "WebSocket closed: code=$code, reason=$reason")
                _sessionState.value = LiveSessionState.Disconnected
            }
        })
    }

    fun sendAudioChunk(pcm16kBytes: ByteArray) {
        val ws = activeWebSocket ?: return
        if (_sessionState.value !is LiveSessionState.Connected) return
        val b64 = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
        val audioMsg = mapOf(
            "realtimeInput" to mapOf(
                "mediaChunks" to listOf(
                    mapOf(
                        "mimeType" to "audio/pcm;rate=16000",
                        "data" to b64
                    )
                )
            )
        )
        ws.send(mapAdapter.toJson(audioMsg))
    }

    private fun handleIncomingMessage(text: String) {
        val parsed = runCatching { mapAdapter.fromJson(text) }.getOrNull() ?: return
        val serverContent = parsed["serverContent"] as? Map<*, *> ?: return
        val modelTurn = serverContent["modelTurn"] as? Map<*, *> ?: return
        val parts = (modelTurn["parts"] as? List<*>).orEmpty()

        for (p in parts) {
            val partMap = p as? Map<*, *> ?: continue
            val inlineData = partMap["inlineData"] as? Map<*, *> ?: continue
            val dataBase64 = inlineData["data"] as? String ?: continue
            if (dataBase64.isNotEmpty()) {
                val pcm24k = Base64.decode(dataBase64, Base64.DEFAULT)
                playLivePcmChunk(pcm24k)
            }
        }
    }

    private fun playLivePcmChunk(pcmBytes: ByteArray) {
        scope.launch {
            val sampleRate = 24000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, pcmBytes.size)

            val track = activeAudioTrack ?: AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            ).also {
                activeAudioTrack = it
                it.play()
            }

            track.write(pcmBytes, 0, pcmBytes.size)
        }
    }

    fun disconnect() {
        runCatching {
            activeWebSocket?.close(1000, "Normal closure")
            activeWebSocket = null
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
            activeAudioTrack = null
        }
        _sessionState.value = LiveSessionState.Disconnected
    }
}
