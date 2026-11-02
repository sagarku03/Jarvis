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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        const val LIVE_MODEL = "gemini-3.1-flash-live-preview"
        private const val LIVE_WS_BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapAdapter = moshi.adapter(Map::class.java)

    private val _sessionState = MutableStateFlow<LiveSessionState>(LiveSessionState.Disconnected)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    val isConnected: Boolean
        get() = _sessionState.value is LiveSessionState.Connected

    private var activeWebSocket: WebSocket? = null
    private var activeAudioTrack: AudioTrack? = null
    private var audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null
    private val playbackLock = Any()

    var onAudioStarted: (() -> Unit)? = null
    var onAudioChunkReceived: ((ByteArray) -> Unit)? = null
    var onTextReceived: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null

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
        DiagnosticsLogger.log("LiveWebSocket", "Connecting to Gemini Live WebSocket ($LIVE_MODEL) with voice: $voiceName")

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

    fun sendAudioChunk(pcm16kBytes: ByteArray): Boolean {
        val ws = activeWebSocket ?: return false
        if (_sessionState.value !is LiveSessionState.Connected) return false
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
        return ws.send(mapAdapter.toJson(audioMsg))
    }

    fun sendAudioChunk(pcm16kShorts: ShortArray): Boolean {
        val bytes = ByteArray(pcm16kShorts.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm16kShorts) {
            buffer.putShort(s)
        }
        return sendAudioChunk(bytes)
    }

    fun sendRealtimeMediaChunk(mimeType: String, dataBase64: String): Boolean {
        val ws = activeWebSocket ?: return false
        if (_sessionState.value !is LiveSessionState.Connected) return false
        val audioMsg = mapOf(
            "realtimeInput" to mapOf(
                "mediaChunks" to listOf(
                    mapOf(
                        "mimeType" to mimeType,
                        "data" to dataBase64
                    )
                )
            )
        )
        return ws.send(mapAdapter.toJson(audioMsg))
    }

    fun sendTextPrompt(text: String, endOfTurn: Boolean = true): Boolean {
        val ws = activeWebSocket ?: return false
        if (_sessionState.value !is LiveSessionState.Connected) return false
        val clientTurn = mapOf(
            "clientContent" to mapOf(
                "turns" to listOf(
                    mapOf(
                        "role" to "user",
                        "parts" to listOf(
                            mapOf("text" to text)
                        )
                    )
                ),
                "turnComplete" to endOfTurn
            )
        )
        return ws.send(mapAdapter.toJson(clientTurn))
    }

    private fun handleIncomingMessage(text: String) {
        val parsed = runCatching { mapAdapter.fromJson(text) }.getOrNull() ?: return
        val serverContent = parsed["serverContent"] as? Map<*, *> ?: return

        val turnComplete = serverContent["turnComplete"] as? Boolean ?: false
        if (turnComplete) {
            onTurnComplete?.invoke()
        }

        val interrupted = serverContent["interrupted"] as? Boolean ?: false
        if (interrupted) {
            stopAudioPlayback()
        }

        val modelTurn = serverContent["modelTurn"] as? Map<*, *> ?: return
        val parts = (modelTurn["parts"] as? List<*>).orEmpty()

        for (p in parts) {
            val partMap = p as? Map<*, *> ?: continue
            val inlineData = partMap["inlineData"] as? Map<*, *>
            if (inlineData != null) {
                val dataBase64 = inlineData["data"] as? String ?: ""
                if (dataBase64.isNotEmpty()) {
                    val pcm24k = Base64.decode(dataBase64, Base64.DEFAULT)
                    onAudioChunkReceived?.invoke(pcm24k)
                    playLivePcmChunk(pcm24k)
                }
            }
            val textSnippet = partMap["text"] as? String
            if (!textSnippet.isNullOrEmpty()) {
                onTextReceived?.invoke(textSnippet)
            }
        }
    }

    private fun playLivePcmChunk(pcmBytes: ByteArray) {
        onAudioStarted?.invoke()
        ensurePlaybackLoop()
        audioChannel.trySend(pcmBytes)
    }

    private fun ensurePlaybackLoop() {
        synchronized(playbackLock) {
            if (playbackJob?.isActive == true && activeAudioTrack != null) return

            val sampleRate = 24000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize * 2, 8192)

            val track = try {
                AudioTrack(
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
                    it.play()
                    activeAudioTrack = it
                }
            } catch (e: Exception) {
                DiagnosticsLogger.log("LiveWebSocket", "Failed to initialize AudioTrack: ${e.message}")
                return
            }

            playbackJob = scope.launch(Dispatchers.IO) {
                try {
                    for (chunk in audioChannel) {
                        if (chunk.isNotEmpty()) {
                            var written = 0
                            while (written < chunk.size && playbackJob?.isActive == true) {
                                val res = track.write(chunk, written, chunk.size - written)
                                if (res <= 0) break
                                written += res
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching {
                        track.stop()
                        track.release()
                    }
                    if (activeAudioTrack === track) {
                        activeAudioTrack = null
                    }
                }
            }
        }
    }

    fun stopAudioPlayback() {
        synchronized(playbackLock) {
            playbackJob?.cancel()
            playbackJob = null
            runCatching {
                activeAudioTrack?.stop()
                activeAudioTrack?.flush()
                activeAudioTrack?.release()
            }
            activeAudioTrack = null
            audioChannel.close()
            audioChannel = Channel(Channel.UNLIMITED)
        }
    }

    fun disconnect() {
        runCatching {
            activeWebSocket?.close(1000, "Normal closure")
            activeWebSocket = null
            stopAudioPlayback()
        }
        _sessionState.value = LiveSessionState.Disconnected
    }
}
