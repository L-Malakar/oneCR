package com.lmalakar.onecr.core.common

/** Injectable clock so time-dependent logic stays testable. */
interface TimeProvider {
    fun nowMillis(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Minimal logging abstraction; the Android Log-backed impl lands with the services (M2). */
interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, error: Throwable? = null)
}

class PrintlnLogger : Logger {
    override fun d(tag: String, message: String) = println("D/[$tag] $message")
    override fun w(tag: String, message: String) = println("W/[$tag] $message")
    override fun e(tag: String, message: String, error: Throwable?) {
        println("E/[$tag] $message${error?.let { " — ${it.message}" } ?: ""}")
    }
}
