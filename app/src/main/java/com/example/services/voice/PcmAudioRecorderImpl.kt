package com.example.services.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.safety.KillSwitch
import com.example.domain.voice.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Stage 2: 16kHz Mono 16-bit PCM AudioRecorder with adaptive Noise Gate and Silence Endpointing.
 * Strict privacy policy: Raw PCM frames are processed strictly in-memory and never logged or stored.
 */
@Singleton
class PcmAudioRecorderImpl @Inject constructor(
    private val context: Context,
    killSwitch: KillSwitch
) : AudioRecorder {

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SPEECH_THRESHOLD_DB = -36f
    }

    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _rmsDbLevel = MutableStateFlow(0f)
    override val rmsDbLevel: StateFlow<Float> = _rmsDbLevel.asStateFlow()

    private val _noiseFloorDb = MutableStateFlow(-48f)
    override val noiseFloorDb: StateFlow<Float> = _noiseFloorDb.asStateFlow()

    private val _silenceEndpointTriggered = MutableStateFlow(false)
    override val silenceEndpointTriggered: StateFlow<Boolean> = _silenceEndpointTriggered.asStateFlow()

    init {
        killSwitch.registerCancelCallback {
            stopRecording()
        }
    }

    @SuppressLint("MissingPermission")
    override fun startRecording(
        enableSilenceEndpointing: Boolean,
        silenceDurationMs: Long,
        onSilenceEndpoint: (() -> Unit)?,
        onPcmFrame: (ShortArray) -> Unit
    ): Boolean {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            return false
        }

        stopRecording()
        _silenceEndpointTriggered.value = false

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(3200)

        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferBytes * 2
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            audioRecord = record
            record.startRecording()
            _isRecording.value = true

            recordingJob = recorderScope.launch {
                val frameBuffer = ShortArray(minBufferBytes / 2)
                var hasDetectedSpeech = false
                var silenceStartMs = 0L

                while (isActive && _isRecording.value) {
                    val readCount = record.read(frameBuffer, 0, frameBuffer.size)
                    if (readCount > 0) {
                        val slice = frameBuffer.copyOf(readCount)
                        val rawDb = computeRawDb(slice)
                        val normalized = ((rawDb + 60f) / 60f).coerceIn(0f, 1f)
                        _rmsDbLevel.value = normalized

                        // Adaptive noise floor tracking during quiet frames
                        if (rawDb < SPEECH_THRESHOLD_DB) {
                            _noiseFloorDb.value = (_noiseFloorDb.value * 0.92f) + (rawDb * 0.08f)
                        }

                        // Noise Gate: suppress low-energy background hum
                        val denoisedSlice = if (rawDb < _noiseFloorDb.value + 4f) {
                            ShortArray(slice.size)
                        } else {
                            slice
                        }
                        onPcmFrame(denoisedSlice)

                        // Silence Endpointing (end-of-speech detection)
                        if (enableSilenceEndpointing) {
                            val now = System.currentTimeMillis()
                            if (rawDb >= SPEECH_THRESHOLD_DB) {
                                hasDetectedSpeech = true
                                silenceStartMs = 0L
                            } else if (hasDetectedSpeech) {
                                if (silenceStartMs == 0L) {
                                    silenceStartMs = now
                                } else if (now - silenceStartMs >= silenceDurationMs) {
                                    _silenceEndpointTriggered.value = true
                                    onSilenceEndpoint?.invoke()
                                    break
                                }
                            }
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            stopRecording()
            false
        }
    }

    override fun stopRecording() {
        _isRecording.value = false
        _rmsDbLevel.value = 0f
        recordingJob?.cancel()
        recordingJob = null
        runCatching {
            audioRecord?.stop()
        }
        runCatching {
            audioRecord?.release()
        }
        audioRecord = null
    }

    private fun computeRawDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -60f
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / samples.size)
        return (20 * log10(rms.coerceAtLeast(1e-5))).toFloat().coerceIn(-60f, 0f)
    }
}
