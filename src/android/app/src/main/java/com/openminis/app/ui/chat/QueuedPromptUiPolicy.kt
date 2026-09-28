package com.openminis.app.ui.chat

/** Pure UI projections for queued prompts; keeps delivery/lifecycle semantics testable on the JVM. */
internal object QueuedPromptUiPolicy {
    fun deliveryForMessage(prompt: QueuedPrompt): QueuedPromptDelivery = prompt.delivery

    fun canRetry(isQueued: Boolean, isStreaming: Boolean): Boolean =
        !isQueued && !isStreaming

    fun canWithdraw(state: QueuedPromptState): Boolean =
        state.lifecycle == QueuedPromptLifecycle.ENQUEUED
}
