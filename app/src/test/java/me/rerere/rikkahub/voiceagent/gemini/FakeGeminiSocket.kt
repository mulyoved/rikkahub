package me.rerere.rikkahub.voiceagent.gemini

class FakeGeminiSocket : GeminiSocket {
    var openedUrl: String? = null
        private set
    var openedToken: String? = null
        private set
    val sentMessages = mutableListOf<String>()
    val sendResults = ArrayDeque<Boolean>()
    var closeCount = 0
        private set

    private var onMessage: ((String) -> Unit)? = null
    private var onClosed: ((Int, String) -> Unit)? = null
    private var onFailure: ((Throwable) -> Unit)? = null

    override fun open(
        url: String,
        token: String,
        onMessage: (String) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        openedUrl = url
        openedToken = token
        this.onMessage = onMessage
        this.onClosed = onClosed
        this.onFailure = onFailure
    }

    override fun send(text: String): Boolean {
        sentMessages += text
        return sendResults.removeFirstOrNull() ?: true
    }

    override fun close() {
        closeCount++
    }

    fun receive(text: String) {
        onMessage?.invoke(text)
    }

    fun closeFromServer(code: Int = 1000, reason: String = "done") {
        onClosed?.invoke(code, reason)
    }

    fun fail(error: Throwable) {
        onFailure?.invoke(error)
    }
}
