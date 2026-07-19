package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch

internal class RetryableRetirement {
    private val lock = Any()
    private var activeAttempt: Attempt? = null
    private var retired = false

    fun retire(block: () -> Unit) {
        val currentThread = Thread.currentThread()
        var ownsAttempt = false
        val attempt = synchronized(lock) {
            if (retired) return
            activeAttempt?.also { currentAttempt ->
                if (currentAttempt.ownerThread === currentThread) return
            } ?: Attempt(currentThread).also { newAttempt ->
                activeAttempt = newAttempt
                ownsAttempt = true
            }
        }

        val result = if (ownsAttempt) {
            runCatching(block).also { attemptResult ->
                synchronized(lock) {
                    attempt.publish(attemptResult)
                    if (attemptResult.isSuccess) retired = true
                    if (activeAttempt === attempt) activeAttempt = null
                }
            }
        } else {
            attempt.awaitResult()
        }
        result.getOrThrow()
    }

    private class Attempt(
        val ownerThread: Thread,
    ) {
        private val completed = CountDownLatch(1)
        private var result: Result<Unit>? = null

        fun publish(value: Result<Unit>) {
            result = value
            completed.countDown()
        }

        fun awaitResult(): Result<Unit> {
            var interrupted = false
            while (true) {
                try {
                    completed.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            return requireNotNull(result)
        }
    }
}
