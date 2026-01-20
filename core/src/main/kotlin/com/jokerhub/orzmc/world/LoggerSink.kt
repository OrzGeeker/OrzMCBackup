package com.jokerhub.orzmc.world

interface LoggerSink {
    fun info(msg: String)
    fun warn(msg: String)
    fun error(msg: String)
}

class ConsoleLoggerSink : LoggerSink {
    override fun info(msg: String) {
        println(msg)
    }

    override fun warn(msg: String) {
        System.err.println(msg)
    }

    override fun error(msg: String) {
        System.err.println(msg)
    }
}
