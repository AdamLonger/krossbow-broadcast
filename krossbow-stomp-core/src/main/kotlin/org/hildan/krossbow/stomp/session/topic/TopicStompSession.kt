package org.hildan.krossbow.stomp.session.topic

import kotlinx.coroutines.flow.Flow
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import org.hildan.krossbow.stomp.session.StompSession

interface TopicStompSession: StompSession {
    /**
     * Subscribes and returns a [Flow] of [MESSAGE][StompFrame.Message] frames that unsubscribes automatically when the
     * collector is done or cancelled.
     * The returned flow can be collected only once.
     *
     * The subscription happens immediately by sending a [SUBSCRIBE][StompFrame.Subscribe] frame with the provided
     * headers.
     * If no subscription ID is provided in the [headers], a generated ID is used in the SUBSCRIBE frame.
     *
     * If no `receipt` header is provided and [auto-receipt][StompConfig.autoReceipt] is enabled, a new unique `receipt`
     * header is generated and added to the SUBSCRIBE frame.
     *
     * If a `receipt` header is present (automatically added or manually provided), this method suspends until the
     * corresponding RECEIPT frame is received from the server.
     * If no RECEIPT frame is received in the configured [time limit][StompConfig.receiptTimeout], a
     * [LostReceiptException] is thrown.
     *
     * If auto-receipt is disabled and no `receipt` header is provided, this method doesn't wait for a RECEIPT frame
     * and never throws [LostReceiptException].
     * Instead, it returns immediately after sending the SUBSCRIBE frame.
     * In this case, there is no real guarantee that the subscription actually happened when this method returns.
     *
     * The unsubscription happens by sending an `UNSUBSCRIBE` frame when the flow collector's coroutine is canceled, or
     * when a terminal operator such as [Flow.first][kotlinx.coroutines.flow.first] completes the flow from the
     * consumer's side.
     *
     * See the general [StompSession] documentation for more details about subscription flows, suspension and receipts.
     *
     * @see subscribeText
     * @see subscribeBinary
     */
    suspend fun subscribe(headers: StompSubscribeHeaders): Flow<StompFrame.Message>
}
