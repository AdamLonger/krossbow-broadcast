package org.hildan.krossbow.stomp.session

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import org.hildan.krossbow.stomp.LostReceiptException
import org.hildan.krossbow.stomp.MissingHeartBeatException
import org.hildan.krossbow.stomp.SessionDisconnectedException
import org.hildan.krossbow.stomp.StompSocket
import org.hildan.krossbow.stomp.config.HeartBeat
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.*
import org.hildan.krossbow.stomp.headers.*
import org.hildan.krossbow.stomp.heartbeats.HeartBeater
import org.hildan.krossbow.stomp.heartbeats.NO_HEART_BEATS
import org.hildan.krossbow.stomp.utils.generateUuid
import org.hildan.krossbow.websocket.WebSocketException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

internal open class BaseStompSession(
    private val config: StompConfig,
    private val stompSocket: StompSocket,
    heartBeat: HeartBeat,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : StompSession {

    protected val scope = CoroutineScope(CoroutineName("stomp-session") + coroutineContext)

    // extra buffer required, so we can wait for the receipt frame from within the onSubscribe
    // of some other shared flow subscription (e.g. in startSubscription())
    protected val sharedStompEvents = MutableSharedFlow<StompEvent>(extraBufferCapacity = 32)

    private val heartBeater = if (heartBeat != NO_HEART_BEATS) {
        HeartBeater(
            heartBeat = heartBeat,
            tolerance = config.heartBeatTolerance,
            sendHeartBeat = {
                // The web socket could have errored or be closed, and the heart beater's job not yet be cancelled.
                // In this case, we don't want the heart beater to crash
                try {
                    stompSocket.sendHeartBeat()
                } catch(e : WebSocketException) {
                    shutdown("STOMP session failed: heart beat couldn't be sent", cause = e)
                }
            },
            onMissingHeartBeat = {
                val cause = MissingHeartBeatException(heartBeat.expectedPeriod)
                sharedStompEvents.emit(StompEvent.Error(cause))
                stompSocket.close(cause)
            },
        )
    } else {
        null
    }

    private val heartBeaterJob = heartBeater?.startIn(scope)

    init {
        scope.launch {
            stompSocket.incomingEvents
                .onEach { heartBeater?.notifyMsgReceived() }
                .materializeErrorsAndCompletion()
                .collect {
                    sharedStompEvents.emit(it)
                }
        }

        scope.launch {
            sharedStompEvents.collect {
                when (it) {
                    is StompEvent.Close -> shutdown("STOMP session disconnected")
                    is StompEvent.Error -> shutdown("STOMP session cancelled due to upstream error", cause = it.cause)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun shutdown(message: String, cause: Throwable? = null) {
        // cancel heartbeats immediately to limit the chances of sending a heartbeat to a closed socket
        heartBeaterJob?.cancel()
        // let other subscribers handle the error/closure before cancelling the scope
        awaitSubscriptionsCompletion()
        scope.cancel(message, cause = cause)
    }

    private suspend fun awaitSubscriptionsCompletion() {
        withTimeoutOrNull(config.subscriptionCompletionTimeout) {
            sharedStompEvents.subscriptionCount.takeWhile { it > 0 }.collect()
        }
    }

    override suspend fun send(headers: StompSendHeaders, body: FrameBody?): StompReceipt? {
        return prepareHeadersAndSendFrame(StompFrame.Send(headers, body))
    }

    @OptIn(UnsafeStompSessionApi::class)
    protected suspend fun prepareHeadersAndSendFrame(frame: StompFrame): StompReceipt? {
        val effectiveFrame = frame.copyWithHeaders {
            maybeSetContentLength(frame.body)
            maybeSetAutoReceipt()
        }
        return sendRawFrameAndMaybeAwaitReceipt(effectiveFrame)
    }

    private fun StompHeadersBuilder.maybeSetContentLength(frameBody: FrameBody?) {
        if (config.autoContentLength && contentLength == null) {
            contentLength = frameBody?.bytes?.size ?: 0
        }
    }

    private fun StompHeadersBuilder.maybeSetAutoReceipt() {
        if (config.autoReceipt && receipt == null) {
            receipt = generateUuid()
        }
    }

    protected suspend fun unsubscribe(subscriptionId: String) {
        sendRawStompFrame(StompFrame.Unsubscribe(StompUnsubscribeHeaders(id = subscriptionId)))
    }

    @UnsafeStompSessionApi
    override suspend fun sendRawFrameAndMaybeAwaitReceipt(frame: StompFrame): StompReceipt? {
        val receiptId = frame.headers.receipt
        if (receiptId == null) {
            sendRawStompFrame(frame)
            return null
        }
        sendRawFrameAndAwaitReceipt(receiptId, frame)
        return StompReceipt(receiptId)
    }

    private suspend fun sendRawFrameAndAwaitReceipt(receiptId: String, frame: StompFrame) {
        withTimeoutOrNull(frame.receiptTimeout) {
            sharedStompEvents
                .onSubscription {
                    sendRawStompFrame(frame)
                }
                .dematerializeErrorsAndCompletion()
                .filterIsInstance<StompFrame.Receipt>()
                .firstOrNull { it.headers.receiptId == receiptId }
                ?: throw SessionDisconnectedException("The STOMP frames flow completed unexpectedly while waiting for RECEIPT frame with id='$receiptId'")
        } ?: throw LostReceiptException(receiptId, frame.receiptTimeout, frame)
    }

    private val StompFrame.receiptTimeout: Duration
        get() = if (command == StompCommand.DISCONNECT) config.disconnectTimeout else config.receiptTimeout

    private suspend fun sendRawStompFrame(frame: StompFrame) {
        stompSocket.sendStompFrame(frame)
        heartBeater?.notifyMsgSent()
    }

    override suspend fun disconnect() {
        if (config.gracefulDisconnect) {
            sendDisconnectFrameAndWaitForReceipt()
        }
        stompSocket.close()
        sharedStompEvents.emit(StompEvent.Close)
    }

    @OptIn(UnsafeStompSessionApi::class)
    private suspend fun sendDisconnectFrameAndWaitForReceipt() {
        try {
            val disconnectFrame = StompFrame.Disconnect(StompDisconnectHeaders { receipt = generateUuid() })
            sendRawFrameAndMaybeAwaitReceipt(disconnectFrame)
        } catch (e: LostReceiptException) {
            // Sometimes the server closes the connection too quickly to send a RECEIPT, which is not really an error
            // http://stomp.github.io/stomp-specification-1.2.html#Connection_Lingering
        }
    }
}

private fun Flow<StompEvent>.materializeErrorsAndCompletion(): Flow<StompEvent> =
    catch { emit(StompEvent.Error(cause = it)) }
        .onCompletion { if (it == null) emit(StompEvent.Close) }

internal fun Flow<StompEvent>.dematerializeErrorsAndCompletion(): Flow<StompEvent> =
    takeWhile { it !is StompEvent.Close }
        .onEach { if (it is StompEvent.Error) throw it.cause }
