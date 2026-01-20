package com.jokerhub.orzmc.world

interface MetricsSink {
    fun incProcessed(n: Long)
    fun incRemoved(n: Long)
    fun recordError(error: OptimizeError)
}

class NoopMetricsSink : MetricsSink {
    override fun incProcessed(n: Long) {}
    override fun incRemoved(n: Long) {}
    override fun recordError(error: OptimizeError) {}
}
