package org.hildan.krossbow.engines.spring

import org.hildan.krossbow.engines.InvalidFramePayloadException
import org.hildan.krossbow.engines.KrossbowMessage
import org.hildan.krossbow.engines.MessageHeaders
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.full.cast

/**
 * An implementation of [StompFrameHandler] that expects messages of type [T] only. It converts each received
 * message's payload into an object of type T and forwards it to the given handler. This is a simple convenience class
 * to make subscriptions simpler.
 *
 * @param <T> the target type for the conversion of the received messages' payload
 */
internal class SingleTypeFrameHandler<T : Any>(
    private val payloadType: KClass<T>,
    private val onReceive: (KrossbowMessage<T>) -> Unit
) : StompFrameHandler {

    override fun getPayloadType(stompHeaders: StompHeaders): Type = payloadType.java

    override fun handleFrame(stompHeaders: StompHeaders, payload: Any?) {
        val headers = stompHeaders.toKrossbowHeaders()
        if (payloadType == Unit::class) {
            @Suppress("UNCHECKED_CAST") onReceive(KrossbowMessage(Unit as T, headers))
            return
        }
        if (payload == null) {
            throw InvalidFramePayloadException("Unsupported null websocket payload, expected ${payloadType.qualifiedName}")
        }
        val typedPayload = payloadType.cast(payload)
        onReceive(KrossbowMessage(typedPayload, headers))
    }
}

/**
 * An implementation of [StompFrameHandler] that expects messages without payloads only.
 */
internal class NoPayloadFrameHandler(
    private val onReceive: (KrossbowMessage<Unit>) -> Unit
) : StompFrameHandler {

    override fun getPayloadType(stompHeaders: StompHeaders): Type = Unit.javaClass

    override fun handleFrame(stompHeaders: StompHeaders, payload: Any?) {
        if (payload != null) {
            throw InvalidFramePayloadException("No payload was expected but some content was received")
        }
        onReceive(KrossbowMessage(Unit, stompHeaders.toKrossbowHeaders()))
    }
}

private fun StompHeaders.toKrossbowHeaders(): MessageHeaders = object : MessageHeaders {} // TODO fill them up