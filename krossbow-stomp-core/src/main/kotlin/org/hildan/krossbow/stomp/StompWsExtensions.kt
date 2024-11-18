package org.hildan.krossbow.stomp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.hildan.krossbow.stomp.config.*
import org.hildan.krossbow.stomp.frame.*
import org.hildan.krossbow.stomp.headers.*
import org.hildan.krossbow.stomp.heartbeats.*
import org.hildan.krossbow.stomp.session.BaseStompSession
import org.hildan.krossbow.stomp.session.StompSession
import org.hildan.krossbow.stomp.session.broadcast.BaseBroadcastStompSession
import org.hildan.krossbow.stomp.session.broadcast.BroadcastStompSession
import org.hildan.krossbow.stomp.session.topic.BaseTopicStompSession
import org.hildan.krossbow.stomp.session.topic.TopicStompSession
import org.hildan.krossbow.stomp.version.*
import org.hildan.krossbow.websocket.*
import kotlin.coroutines.*

/**
 * A constant representing the default value for the host header. It is replaced by the real default header value
 * when actually connecting. This constant is necessary because we want to allow `null` as a user-provided value (which
 * should be distinguishable from the default).
 */
internal const val DefaultHost =
    "<default host header>" // invalid host value to prevent conflicts with real hosts

/**
 * Establishes a STOMP session over an existing [WebSocketConnection].
 *
 * The behavior of the STOMP protocol can be customized via the given [config].
 * However, the semantics of [StompConfig.connectionTimeout] is slightly changed: it doesn't take into account
 * the web socket connection time (since it already happened outside of this method call).
 *
 * If [login] and [passcode] are provided, they are used for STOMP authentication.
 *
 * The CONNECT/STOMP frame can be further customized by using [customHeaders], which may be useful for server-specific
 * behavior, like token-based authentication.
 *
 * If the connection at the STOMP level fails, the underlying web socket is closed.
 */
suspend fun WebSocketConnection.stomp(
    config: StompConfig,
    host: String? = DefaultHost,
    login: String? = null,
    passcode: String? = null,
    customHeaders: Map<String, String> = emptyMap(),
    sessionCoroutineContext: CoroutineContext = EmptyCoroutineContext,
): TopicStompSession {
    val wsStompVersion = StompVersion.fromWsProtocol(protocol)
    val serverPossiblySupportsHost = wsStompVersion == null || wsStompVersion.supportsHostHeader
    val effectiveHost =
        if (host == DefaultHost) this.host.takeIf { serverPossiblySupportsHost } else host
    val connectHeaders = StompConnectHeaders(host = effectiveHost) {
        this.login = login
        this.passcode = passcode
        this.heartBeat = config.heartBeat
        setAll(customHeaders)
    }

    return stomp(
        config = config,
        headers = connectHeaders,
        sessionCoroutineContext = sessionCoroutineContext
    ) { conf, stompSocket, negotiatedHeartBeat, contextOverrides ->
        BaseTopicStompSession(conf, stompSocket, negotiatedHeartBeat, contextOverrides)
    }
}

suspend fun WebSocketConnection.stompBroadcast(
    config: StompConfig,
    host: String? = DefaultHost,
    login: String? = null,
    passcode: String? = null,
    customHeaders: Map<String, String> = emptyMap(),
    sessionCoroutineContext: CoroutineContext = EmptyCoroutineContext,
): BroadcastStompSession {
    val wsStompVersion = StompVersion.fromWsProtocol(protocol)
    val serverPossiblySupportsHost = wsStompVersion == null || wsStompVersion.supportsHostHeader
    val effectiveHost =
        if (host == DefaultHost) this.host.takeIf { serverPossiblySupportsHost } else host
    val connectHeaders = StompConnectHeaders(host = effectiveHost) {
        this.login = login
        this.passcode = passcode
        this.heartBeat = config.heartBeat
        setAll(customHeaders)
    }

    return stomp(
        config = config,
        headers = connectHeaders,
        sessionCoroutineContext = sessionCoroutineContext
    ) { conf, stompSocket, negotiatedHeartBeat, contextOverrides ->
        BaseBroadcastStompSession(conf, stompSocket, negotiatedHeartBeat, contextOverrides)
    }
}

private suspend fun <T> WebSocketConnection.stomp(
    config: StompConfig,
    headers: StompConnectHeaders,
    sessionCoroutineContext: CoroutineContext,
    sessionBuilder: (StompConfig, StompSocket, HeartBeat, CoroutineContext) -> T
): T {
    val stompSocket = StompSocket(this, config)
    try {
        val connectedFrame = withTimeoutOrNull(config.connectionTimeout) {
            stompSocket.connectHandshake(headers, config.connectWithStompCommand)
        } ?: throw ConnectionTimeout(headers.host ?: "null", config.connectionTimeout)

        if (config.failOnStompVersionMismatch) {
            val wsStompVersion = StompVersion.fromWsProtocol(protocol)
            val realStompVersion = StompVersion.fromConnectedFrame(connectedFrame.headers.version)
            check(wsStompVersion == null || wsStompVersion == realStompVersion) {
                "negotiated STOMP version mismatch: " +
                        "$wsStompVersion at web socket level (subprotocol '$protocol'), " +
                        "$realStompVersion at STOMP level"
            }
        }

        val negotiatedHeartBeat = config.heartBeat.negotiated(connectedFrame.headers.heartBeat)
        val contextOverrides = config.defaultSessionCoroutineContext + sessionCoroutineContext
        return sessionBuilder(config, stompSocket, negotiatedHeartBeat, contextOverrides)
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            stompSocket.close(e)
        }
        // this cancellation comes from the outside, we should not wrap this exception
        throw e
    } catch (e: ConnectionTimeout) {
        stompSocket.close(e)
        throw e
    } catch (e: Exception) {
        throw StompConnectionException(headers.host, cause = e)
    }
}

private suspend fun StompSocket.connectHandshake(
    headers: StompConnectHeaders,
    connectWithStompCommand: Boolean,
): StompFrame.Connected = coroutineScope {
    val futureConnectedFrame = async(start = CoroutineStart.UNDISPATCHED) {
        awaitConnectedFrame()
    }
    val connectFrame = if (connectWithStompCommand) {
        StompFrame.Stomp(headers)
    } else {
        StompFrame.Connect(headers)
    }
    sendStompFrame(connectFrame)
    futureConnectedFrame.await()
}

private suspend fun StompSocket.awaitConnectedFrame(): StompFrame.Connected {
    val stompEvent = incomingEvents.first()
    check(stompEvent is StompFrame.Connected) { "Expected CONNECTED frame in response to CONNECT, got $stompEvent" }
    return stompEvent
}
