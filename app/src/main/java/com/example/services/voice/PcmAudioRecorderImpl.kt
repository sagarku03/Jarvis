package com.example.services.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.di.Inject
import com.example.di.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@Singleton
class PcmAudioRecorderImpl @Inject constructor(
    @Suppress("unused") private val context: Context
) : PcmAudioRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _rmsEnergyLevel = MutableStateFlow(0f)
    override val rmsEnergyLevel: StateFlow<Float> = _rmsEnergyLevel.asStateFlow()

    @SuppressLint("MissingPermission")
    override fun startRecording(
        onPcmFrame: (ShortArray) -> Unit,
        onSilenceEndpointDetected: () -> Unit
    ): Boolean {
        stopRecording()
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(1024)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufSize * 2
            )
        } catch (_: Exception) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return false
        }

        audioRecord = record
        _isRecording.value = true

        recordingJob = scope.launch {
            val frameBuffer = ShortArray(512)
            try {
                record.startRecording()
                while (isActive && _isRecording.value) {
                    val readCount = record.read(frameBuffer, 0, frameBuffer.size)
                    if (readCount > 0) {
                        val copy = frameBuffer.copyOf(readCount)
                        onPcmFrame(copy)
                        var sumSquares = 0.0
                        for (i in 0 until readCount) {
                            val norm = copy[i] / 32768.0
                            sumSquares += norm * norm
                        }
                        val rms = sqrt(sumSquares / readCount).toFloat().coerceIn(0f, 1f)
                        _rmsEnergyLevel.value = (rms * 4f).coerceIn(0f, 1f)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore hardware stop errors
            } finally {
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                    record.release()
                }
                _isRecording.value = false
                _rmsEnergyLevel.value = 0f
            }
        }
        return true
    }

    override fun stopRecording() {
        _isRecording.value = false
        _rmsEnergyLevel.value = 0f
        recordingJob?.cancel()
        recordingJob = null
        audioRecord = null
    }
}
