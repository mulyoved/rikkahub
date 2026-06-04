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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidVoiceAudioEngine(context: Context) : VoiceAudioEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val captureCallbackLock = Any()
    private val captureRecordLock = Any()
    private val playbackWriteLock = Any()
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureGeneration = 0L
    private var playbackGeneration = 0L
    private var released = false

    override fun startCapture(onPcm16: (ByteArray) -> Unit) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Microphone permission is required")
        }

        stopCapture()

        val generation = synchronized(lock) {
            check(!released) { "Voice audio engine is released" }
            captureGeneration += 1
            captureGeneration
        }
        val bufferSize = captureBufferSize()
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2,
            )
        }.getOrElse {
            throw IllegalStateException("AudioRecord creation failed", it)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.releaseSafely()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val buffer = ByteArray(bufferSize)
            try {
                captureLoop@ while (isActive && isCurrentCapture(generation, recorder)) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: RuntimeException) {
                        if (isCurrentCapture(generation, recorder)) {
                            Log.w(TAG, "AudioRecord read failed", e)
                        }
                        break@captureLoop
                    }

                    when (read) {
                        in 1..buffer.size -> {
                            deliverCaptureBuffer(generation, recorder, buffer.copyOf(read), onPcm16)
                        }
                        0 -> Unit
                        else -> {
                            if (isCurrentCapture(generation, recorder)) {
                                try {
                                    throw IllegalStateException("AudioRecord read error: $read")
                                } catch (e: IllegalStateException) {
                                    Log.w(TAG, "Stopping capture after AudioRecord read failure", e)
                                }
                            }
                            break@captureLoop
                        }
                    }
                }
            } finally {
                clearRecorder(generation, recorder)
                stopAndReleaseRecorder(recorder)
            }
        }

        var published = false
        synchronized(lock) {
            if (!released && generation == captureGeneration) {
                audioRecord = recorder
                captureJob = job
                published = true
            }
        }

        if (!published || !isCurrentCapture(generation, recorder)) {
            job.cancel()
            releaseRecorder(recorder)
            return
        }

        synchronized(captureRecordLock) {
            if (!isCurrentCapture(generation, recorder)) {
                job.cancel()
                recorder.releaseSafely()
                return
            }

            try {
                recorder.startRecording()
            } catch (e: RuntimeException) {
                val stillCurrent = isCurrentCapture(generation, recorder)
                if (stillCurrent) {
                    clearRecorder(generation, recorder)
                }
                job.cancel()
                recorder.releaseSafely()
                if (stillCurrent) {
                    throw IllegalStateException("AudioRecord start failed", e)
                }
                return
            }

            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val stillCurrent = isCurrentCapture(generation, recorder)
                if (stillCurrent) {
                    clearRecorder(generation, recorder)
                }
                job.cancel()
                recorder.releaseSafely()
                if (stillCurrent) {
                    throw IllegalStateException("AudioRecord start failed")
                }
                return
            }

            if (isCurrentCapture(generation, recorder)) {
                job.start()
            } else {
                job.cancel()
                recorder.stopSafely()
                recorder.releaseSafely()
            }
        }
    }

    override fun stopCapture() {
        val job: Job?
        val recorder: AudioRecord?
        synchronized(lock) {
            captureGeneration += 1
            job = captureJob
            captureJob = null
            recorder = audioRecord
            audioRecord = null
        }
        job?.cancel()
        recorder?.let(::stopAndReleaseRecorder)
        waitForCaptureCallbacks()
    }

    override fun playPcm16(base64Pcm16: String) {
        val pcm16 = runCatching { Base64.decode(base64Pcm16, Base64.NO_WRAP) }
            .onFailure { Log.w(TAG, "Dropping malformed playback chunk", it) }
            .getOrNull()
            ?: return
        if (pcm16.isEmpty()) {
            return
        }

        val generation = synchronized(lock) {
            if (released) {
                return
            }
            playbackGeneration
        }
        val track = getOrCreatePlaybackTrack(generation) ?: return
        synchronized(playbackWriteLock) {
            if (!isCurrentPlayback(generation, track)) {
                return
            }
            if (!track.playSafely()) {
                return
            }
            if (!isCurrentPlayback(generation, track)) {
                return
            }
            track.writeNonBlocking(pcm16, generation)
        }
    }

    override fun suppressPlayback() {
        val track: AudioTrack
        val generation: Long
        synchronized(lock) {
            if (released) {
                return
            }
            playbackGeneration += 1
            track = audioTrack ?: return
            generation = playbackGeneration
        }

        synchronized(playbackWriteLock) {
            if (isCurrentPlayback(generation, track)) {
                val wasPlaying = track.isPlaying()
                track.pauseSafely()
                track.flushSafely()
                if (wasPlaying && isCurrentPlayback(generation, track)) {
                    track.playSafely()
                }
            }
        }
    }

    override fun release() {
        val job: Job?
        val recorder: AudioRecord?
        val track: AudioTrack?
        synchronized(lock) {
            if (released) {
                return
            }
            released = true
            captureGeneration += 1
            playbackGeneration += 1
            job = captureJob
            captureJob = null
            recorder = audioRecord
            audioRecord = null
            track = audioTrack
            audioTrack = null
        }
        job?.cancel()
        recorder?.let(::stopAndReleaseRecorder)
        synchronized(playbackWriteLock) {
            track?.stopSafely()
            track?.releaseSafely()
        }
        waitForCaptureCallbacks()
        scope.cancel()
    }

    private fun clearRecorder(generation: Long, recorder: AudioRecord) {
        synchronized(lock) {
            if (captureGeneration == generation && audioRecord === recorder) {
                captureJob = null
                audioRecord = null
            }
        }
    }

    private fun isCurrentCapture(generation: Long, recorder: AudioRecord): Boolean = synchronized(lock) {
        captureGeneration == generation && audioRecord === recorder
    }

    private fun deliverCaptureBuffer(
        generation: Long,
        recorder: AudioRecord,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        synchronized(captureCallbackLock) {
            if (isCurrentCapture(generation, recorder)) {
                try {
                    onPcm16(buffer)
                } catch (e: Throwable) {
                    Log.w(TAG, "Stopping capture after PCM callback failure", e)
                    clearRecorder(generation, recorder)
                }
            }
        }
    }

    private fun waitForCaptureCallbacks() {
        synchronized(captureCallbackLock) {
            // Wait for any in-flight capture callback that passed its final generation check.
        }
    }

    private fun stopAndReleaseRecorder(recorder: AudioRecord) {
        synchronized(captureRecordLock) {
            recorder.stopSafely()
            recorder.releaseSafely()
        }
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        synchronized(captureRecordLock) {
            recorder.releaseSafely()
        }
    }

    private fun isCurrentPlayback(generation: Long, track: AudioTrack): Boolean = synchronized(lock) {
        playbackGeneration == generation && audioTrack === track
    }

    private fun getOrCreatePlaybackTrack(generation: Long): AudioTrack? {
        val existingTrack = synchronized(lock) { audioTrack }
        if (existingTrack != null) {
            return currentPlaybackTrack(existingTrack, generation)
        }

        val newTrack = createAudioTrackOrNull() ?: return null
        var selectedTrack: AudioTrack? = null
        var shouldReleaseNewTrack = false
        synchronized(lock) {
            val currentTrack = audioTrack
            if (released || playbackGeneration != generation) {
                shouldReleaseNewTrack = true
            } else if (currentTrack == null) {
                audioTrack = newTrack
                selectedTrack = newTrack
            } else {
                selectedTrack = currentTrack
                shouldReleaseNewTrack = true
            }
        }

        if (shouldReleaseNewTrack) {
            newTrack.releaseSafely()
        }
        return currentPlaybackTrack(selectedTrack ?: return null, generation)
    }

    private fun currentPlaybackTrack(track: AudioTrack, generation: Long): AudioTrack? = synchronized(lock) {
        if (!released && playbackGeneration == generation && audioTrack === track) {
            track
        } else {
            null
        }
    }

    private fun createAudioTrackOrNull(): AudioTrack? {
        val bufferSize = playbackBufferSizeOrNull() ?: return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val track = runCatching {
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.onFailure {
            Log.w(TAG, "AudioTrack creation failed", it)
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
            track.releaseSafely()
            return null
        }

        return track
    }

    private fun removeTrack(track: AudioTrack) {
        synchronized(lock) {
            if (audioTrack === track) {
                playbackGeneration += 1
                audioTrack = null
            }
        }
    }

    private fun AudioTrack.writeNonBlocking(pcm16: ByteArray, generation: Long) {
        var offset = 0
        var attempts = 0
        while (offset < pcm16.size && attempts < MAX_NON_BLOCKING_WRITE_ATTEMPTS) {
            if (!isCurrentPlayback(generation, this)) {
                return
            }

            val remaining = pcm16.size - offset
            val writeResult = runCatching {
                write(pcm16, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
            }.onFailure {
                Log.w(TAG, "AudioTrack write failed", it)
                removeTrack(this)
                releaseSafely()
            }.getOrNull() ?: return

            when {
                writeResult < 0 -> {
                    Log.w(TAG, "AudioTrack write error: $writeResult")
                    removeTrack(this)
                    releaseSafely()
                    return
                }
                writeResult == 0 -> {
                    attempts += 1
                    Thread.yield()
                }
                else -> {
                    offset += writeResult.coerceAtMost(remaining)
                    attempts += 1
                }
            }
        }

        if (offset < pcm16.size && isCurrentPlayback(generation, this)) {
            Log.w(
                TAG,
                "AudioTrack non-blocking write incomplete: wrote=$offset requested=${pcm16.size}; " +
                    "dropping ${pcm16.size - offset} bytes",
            )
        }
    }

    private fun AudioTrack.isPlaying(): Boolean {
        return runCatching { playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)
    }

    private fun AudioTrack.playSafely(): Boolean {
        return runCatching {
            if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                play()
            }
            true
        }.onFailure {
            Log.w(TAG, "AudioTrack play failed", it)
            removeTrack(this)
            releaseSafely()
        }.getOrDefault(false)
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
        const val TAG = "AndroidVoiceAudioEngine"
        const val CAPTURE_SAMPLE_RATE = 16_000
        const val PLAYBACK_SAMPLE_RATE = 24_000
        const val MIN_CAPTURE_BUFFER_BYTES = 3_200
        const val MIN_PLAYBACK_BUFFER_BYTES = 4_800
        const val MAX_NON_BLOCKING_WRITE_ATTEMPTS = 8

        fun captureBufferSize(): Int {
            val bufferSize = AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                throw IllegalStateException("AudioRecord min buffer size failed: $bufferSize")
            }
            return bufferSize.coerceAtLeast(MIN_CAPTURE_BUFFER_BYTES)
        }

        fun playbackBufferSizeOrNull(): Int? {
            val bufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                Log.w(TAG, "AudioTrack min buffer size failed: $bufferSize")
                return null
            }
            return bufferSize.coerceAtLeast(MIN_PLAYBACK_BUFFER_BYTES)
        }
    }
}
