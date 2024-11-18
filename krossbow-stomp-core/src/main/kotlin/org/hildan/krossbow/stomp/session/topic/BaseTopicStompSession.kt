package org.hildan.krossbow.stomp.session.topic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.io.bytestring.ByteString
import org.hildan.krossbow.stomp.StompSocket
import org.hildan.krossbow.stomp.config.HeartBeat
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import org.hildan.krossbow.stomp.session.BaseStompSession
import org.hildan.krossbow.stomp.session.StompSession
import org.hildan.krossbow.stomp.session.dematerializeErrorsAndCompletion
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class BaseTopicStompSession(
    config: StompConfig,
    stompSocket: StompSocket,
    heartBeat: HeartBeat,
    coroutineContext: CoroutineContext = EmptyCoroutineContext
) : BaseStompSession(
    config = config,
    stompSocket = stompSocket,
    heartBeat = heartBeat,
    coroutineContext = coroutineContext
), TopicStompSession {
    override suspend fun subscribe(headers: StompSubscribeHeaders): Flow<StompFrame.Message> {
        return startSubscription(headers)
            .consumeAsFlow()
            .onCompletion {
                when (it) {
                    // If the consumer was cancelled or an exception occurred downstream, the STOMP session keeps going
                    // so we want to unsubscribe this failed subscription.
                    // Note that calling .first() actually cancels the flow with CancellationException, so it's
                    // covered here.
                    is CancellationException -> {
                        if (scope.isActive) {
                            unsubscribe(headers.id)
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
    }

    private suspend fun startSubscription(headers: StompSubscribeHeaders): ReceiveChannel<StompFrame.Message> {
        val subscriptionStarted = CompletableDeferred<Unit>()

        val subscriptionChannel = sharedStompEvents
            .onSubscription {
                try {
                    // ensures we are already listening for frames before sending SUBSCRIBE, so we don't miss messages
                    prepareHeadersAndSendFrame(StompFrame.Subscribe(headers))
                    subscriptionStarted.complete(Unit)
                } catch (e: Exception) {
                    subscriptionStarted.completeExceptionally(e)
                }
            }
            .dematerializeErrorsAndCompletion()
            .filterIsInstance<StompFrame.Message>()
            .filter { it.headers.subscription == headers.id }
            .produceIn(scope)

        // Ensures we actually subscribe now, to conform to the semantics of subscribe().
        // produceIn() on its own cannot guarantee that the producer coroutine has started when it returns
        subscriptionStarted.await()
        return subscriptionChannel
    }

}

/**
 * Subscribes and returns a [Flow] of [MESSAGE][StompFrame.Message] frames that unsubscribes automatically when the
 * collector is done or cancelled.
 * The returned flow can be collected only once.
 *
 * See the general [StompSession] documentation for more details about subscription flows, suspension and receipts.
 *
 * @see subscribeBinary
 * @see subscribeText
 */
suspend fun TopicStompSession.subscribe(destination: String): Flow<StompFrame.Message> =
    subscribe(StompSubscribeHeaders(destination))

/**
 * Subscribes and returns a [Flow] of text message bodies that unsubscribes automatically when the collector is done or
 * cancelled.
 * The returned flow can be collected only once.
 *
 * The received MESSAGE frames' bodies are expected to be decodable as text: they must come from a textual web socket
 * frame, or their `content-type` header should start with `text/` or contain a `charset` parameter (see
 * [StompFrame.bodyAsText]).
 * If a received frame is not decodable as text, an exception is thrown.
 *
 * Frames without a body are indistinguishable from frames with a 0-length body, and therefore result in an empty
 * string in the subscription flow.
 *
 * See the general [StompSession] documentation for more details about subscription flows, suspension and receipts.
 */
suspend fun TopicStompSession.subscribeText(destination: String): Flow<String> =
    subscribe(destination).map { it.bodyAsText }

/**
 * Subscribes and returns a [Flow] of binary message bodies that unsubscribes automatically when the collector is done
 * or cancelled.
 * The returned flow can be collected only once.
 *
 * Frames without a body are indistinguishable from frames with a 0-length body, and therefore result in an empty
 * [ByteArray] in the subscription flow.
 *
 * See the general [StompSession] documentation for more details about subscription flows, suspension and receipts.
 */
suspend fun TopicStompSession.subscribeBinary(destination: String): Flow<ByteString> =
    subscribe(destination).map { it.body?.bytes ?: ByteString() }
