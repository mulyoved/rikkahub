package me.rerere.rikkahub.voiceagent.audio

interface VoiceAudioEngine {
    fun startCapture(onPcm16: (ByteArray) -> Unit)
    fun stopCapture()
    fun playPcm16(base64Pcm16: String)
    fun suppressPlayback()
    fun release()
}
