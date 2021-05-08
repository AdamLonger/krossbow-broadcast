package org.hildan.krossbow.websocket.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

@OptIn(DelicateCoroutinesApi::class)
actual fun runSuspendingTest(block: suspend CoroutineScope.() -> Unit): dynamic = GlobalScope.promise { block() }

internal actual suspend fun runAlongEchoWSServer(block: suspend (port: Int) -> Unit) {
    TODO("Implement test WS echo server on JS platform")
}
