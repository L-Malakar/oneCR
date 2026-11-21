package com.lmalakar.onecr.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Indirection over [Dispatchers] so services and repositories stay testable
 * (tests inject a test dispatcher through this interface).
 */
interface DispatchersProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class DefaultDispatchersProvider : DispatchersProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}
