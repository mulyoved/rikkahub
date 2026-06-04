package me.rerere.rikkahub.voiceagent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidVoiceAudioEngine(
    private val context: Context,
) : VoiceAudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    override fun startCapture(onPcm16: (ByteArray) -> Unit) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Microphone permission is required")
        }

        stopCapture()

        val job = scope.launch {
            val bufferSize = captureBufferSize()
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2,
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.releaseSafely()
                throw IllegalStateException("AudioRecord initialization failed")
            }

            synchronized(lock) {
                if (!isActive) {
                    recorder.releaseSafely()
                    return@launch
                }
                audioRecord = recorder
            }

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> onPcm16(buffer.copyOf(read))
                        read < 0 -> throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } finally {
                if (clearRecorder(recorder)) {
                    recorder.stopSafely()
                    recorder.releaseSafely()
                }
            }
        }

        synchronized(lock) {
            captureJob = job
        }
    }

    override fun stopCapture() {
        val job: Job?
        val recorder: AudioRecord?
        synchronized(lock) {
            job = captureJob
            captureJob = null
            recorder = audioRecord
            audioRecord = null
        }
        job?.cancel()
        recorder?.stopSafely()
        recorder?.releaseSafely()
    }

    override fun playPcm16(base64Pcm16: String) {
        val pcm16 = Base64.decode(base64Pcm16, Base64.NO_WRAP)
        synchronized(lock) {
            val track = audioTrack ?: createAudioTrack().also { audioTrack = it }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(pcm16, 0, pcm16.size)
        }
    }

    override fun suppressPlayback() {
        synchronized(lock) {
            val track = audioTrack ?: return
            val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
            track.pauseSafely()
            track.flushSafely()
            if (wasPlaying) {
                track.playSafely()
            }
        }
    }

    override fun release() {
        stopCapture()
        val track: AudioTrack?
        synchronized(lock) {
            track = audioTrack
            audioTrack = null
        }
        track?.stopSafely()
        track?.releaseSafely()
        scope.cancel()
    }

    private fun clearRecorder(recorder: AudioRecord): Boolean = synchronized(lock) {
        if (audioRecord === recorder) {
            audioRecord = null
            true
        } else {
            false
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack(
            attributes,
            format,
            playbackBufferSize(),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    private fun AudioRecord.stopSafely() {
        runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
        }
    }

    private fun AudioRecord.releaseSafely() {
        runCatching { release() }
    }

    private fun AudioTrack.playSafely() {
        runCatching { play() }
    }

    private fun AudioTrack.pauseSafely() {
        runCatching { pause() }
    }

    private fun AudioTrack.flushSafely() {
        runCatching { flush() }
    }

    private fun AudioTrack.stopSafely() {
        runCatching { stop() }
    }

    private fun AudioTrack.releaseSafely() {
        runCatching { release() }
    }

    private companion object {
        const val CAPTURE_SAMPLE_RATE = 16_000
        const val PLAYBACK_SAMPLE_RATE = 24_000
        const val MIN_CAPTURE_BUFFER_BYTES = 3_200
        const val MIN_PLAYBACK_BUFFER_BYTES = 4_800

        fun captureBufferSize(): Int {
            return AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(MIN_CAPTURE_BUFFER_BYTES)
        }

        fun playbackBufferSize(): Int {
            return AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(MIN_PLAYBACK_BUFFER_BYTES)
        }
    }
}
