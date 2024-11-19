package org.hildan.krossbow.stomp.session.broadcast

import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.hildan.krossbow.stomp.StompSocket
import org.hildan.krossbow.stomp.config.HeartBeat
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import org.hildan.krossbow.stomp.headers.StompUnsubscribeHeaders
import org.hildan.krossbow.stomp.session.BaseStompSession
import org.hildan.krossbow.stomp.session.dematerializeErrorsAndCompletion
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class BaseBroadcastStompSession(
    config: StompConfig,
    stompSocket: StompSocket,
    heartBeat: HeartBeat,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : BaseStompSession(
    config = config,
    stompSocket = stompSocket,
    heartBeat = heartBeat,
    coroutineContext = coroutineContext
), BroadcastStompSession {
    private val semaphore = Semaphore(1)
    private val destinationRequests = ConcurrentHashMap<String, BroadcastTopicRequest.Subscribe>()
    private val requests: Channel<BroadcastTopicRequest> = Channel(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            requests.receiveAsFlow().collect { request ->
                when (request) {
                    is BroadcastTopicRequest.Subscribe -> subscribe(request)
                    is BroadcastTopicRequest.Unsubscribe -> unsubscribe(request)
                }
            }
        }
    }

    override val messages: ReceiveChannel<StompFrame.Message> = sharedStompEvents
        .dematerializeErrorsAndCompletion()
        .onCompletion {
            when (it) {
                // If the consumer was cancelled or an exception occurred downstream, the STOMP session keeps going
                // so we want to unsubscribe this failed subscription.
                // Note that calling .first() actually cancels the flow with CancellationException, so it's
                // covered here.
                is CancellationException -> {
                    if (scope.isActive) {
                        val headerIds = destinationRequests.keys.toSet()
                        headerIds.forEach { id -> unsubscribe(id) }
                    } else {
                        // The whole session is cancelled, the web socket must be already closed
                    }
                }
                // If the flow completes normally, it means the frames channel is closed, and so is the web socket
                // connection. We can't send an unsubscribe frame in this case.
                // If an exception is thrown upstream, it means there was a STOMP or web socket error and we can't
                // unsubscribe either.
                else -> Unit
            }
        }
        .filterIsInstance<StompFrame.Message>()
        .produceIn(scope)

    private val mutableDestinations =
        MutableStateFlow<ImmutableSet<String>>(persistentSetOf())

    override val destinations: StateFlow<ImmutableSet<String>> =
        mutableDestinations.asStateFlow()

    private suspend fun subscribe(request: BroadcastTopicRequest.Subscribe) {
        semaphore.withPermit {
            if (destinationRequests.contains(key = request.headers.id)) return@withPermit
            destinationRequests[request.headers.id] = request

            prepareHeadersAndSendFrame(StompFrame.Subscribe(request.headers))
        }
    }

    private suspend fun unsubscribe(request: BroadcastTopicRequest.Unsubscribe) {
        semaphore.withPermit {
            if (!destinationRequests.contains(key = request.headers.id)) return@withPermit
            destinationRequests.remove(request.headers.id)

            prepareHeadersAndSendFrame(StompFrame.Unsubscribe(request.headers))
        }
    }

    override suspend fun subscribeTopic(headers: StompSubscribeHeaders) =
        requests.send(BroadcastTopicRequest.Subscribe(headers))

    override suspend fun unsubscribeTopic(headers: StompUnsubscribeHeaders) =
        requests.send(BroadcastTopicRequest.Unsubscribe(headers))
}
