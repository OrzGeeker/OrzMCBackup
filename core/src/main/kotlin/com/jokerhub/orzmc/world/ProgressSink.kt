package com.jokerhub.orzmc.world

/** Receives [ProgressEvent] notifications during optimization. */
interface ProgressSink {
    fun emit(event: ProgressEvent)
}

/** A [ProgressSink] that discards all events. */
object NoopProgressSink : ProgressSink {
    override fun emit(event: ProgressEvent) {}
}

/** A [ProgressSink] that forwards events to a callback function. */
class CallbackProgressSink(
    private val callback: ((ProgressEvent) -> Unit)?,
) : ProgressSink {
    override fun emit(event: ProgressEvent) {
        callback?.invoke(event)
    }
}

/** Convenience function to create a [CallbackProgressSink] from a lambda. */
fun progressSink(callback: (ProgressEvent) -> Unit): ProgressSink = CallbackProgressSink(callback)
