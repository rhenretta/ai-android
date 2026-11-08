package com.personal.pixelagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Full-duplex audio pipeline tuned for the Pixel 7 Pro:
 * - 16 kHz mono 16-bit PCM capture with hardware AcousticEchoCanceler & NoiseSuppressor
 * - 24 kHz mono 16-bit PCM streaming playback routed to speakerphone (or connected headset/earbuds)
 * - Instant playback buffer flush on user barge-in interruption
 */
class AudioDuplexController(
    context: Context,
    private val scope: CoroutineScope,
    private val onMicPcm16kChunk: (ByteArray, Int) -> Unit
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var recordJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    @Volatile
    var isMuted: Boolean = false

    private val _rmsLevel = MutableStateFlow(0.08f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start() {
        stop()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToHandsFreeSpeakerIfNoHeadset()

        val minInBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minInBuf * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone AudioRecord failed to initialize. Verify RECORD_AUDIO permission.")
        }
        audioRecord = record

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        val minOutBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minOutBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()
        record.startRecording()

        playbackChannel = Channel(capacity = Channel.UNLIMITED)

        recordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(3200) // 100ms of 16kHz 16-bit mono PCM
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (!isMuted) {
                        _rmsLevel.value = computeNormalizedRms(buffer, read)
                        onMicPcm16kChunk(buffer.copyOf(read), read)
                    } else {
                        _rmsLevel.value = 0.05f
                    }
                }
            }
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            for (chunk in playbackChannel) {
                if (!isActive) break
                val currentTrack = audioTrack ?: continue
                _rmsLevel.value = computeNormalizedRms(chunk, chunk.size)
                try {
                    currentTrack.write(chunk, 0, chunk.size)
                } catch (_: IllegalStateException) {
                    // Track flushed or stopped
                }
            }
        }
    }

    fun enqueueSpeakerPcm24k(pcmBytes: ByteArray) {
        playbackChannel.trySend(pcmBytes)
    }

    fun flushPlaybackImmediately() {
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain queued model audio on user barge-in
        }
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
                track.play()
            } catch (_: IllegalStateException) {
                // Ignore if already stopped
            }
        }
    }

    fun stop() {
        recordJob?.cancel()
        recordJob = null
        playbackJob?.cancel()
        playbackJob = null
        playbackChannel.close()

        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null

        audioRecord?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                rec.release()
            }
        }
        audioRecord = null

        audioTrack?.let { trk ->
            runCatching {
                trk.pause()
                trk.flush()
                trk.stop()
                trk.release()
            }
        }
        audioTrack = null

        runCatching {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        _rmsLevel.value = 0.05f
    }

    private fun routeToHandsFreeSpeakerIfNoHeadset() {
        val devices = audioManager.availableCommunicationDevices
        val externalHeadset = devices.firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (externalHeadset != null) {
            audioManager.setCommunicationDevice(externalHeadset)
        } else {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { speaker ->
                audioManager.setCommunicationDevice(speaker)
            }
        }
    }

    private fun computeNormalizedRms(pcm16Le: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.08f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += (sample * sample).toDouble()
            i += 2
        }
        val rms = sqrt(sumSq / sampleCount) / 8000.0
        return min(1.0f, rms.toFloat().coerceAtLeast(0.08f))
    }

    companion object {
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
    }
}
