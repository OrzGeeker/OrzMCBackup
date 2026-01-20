package com.jokerhub.orzmc.world

interface ProgressSink {
    fun emit(event: ProgressEvent)
}

class CallbackProgressSink(private val callback: ((ProgressEvent) -> Unit)?) : ProgressSink {
    override fun emit(event: ProgressEvent) {
        callback?.invoke(event)
    }
}
