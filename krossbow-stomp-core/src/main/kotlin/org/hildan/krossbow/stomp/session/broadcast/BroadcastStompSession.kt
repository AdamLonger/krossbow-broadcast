package org.hildan.krossbow.stomp.session.broadcast

import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import org.hildan.krossbow.stomp.headers.StompUnsubscribeHeaders
import org.hildan.krossbow.stomp.session.StompSession

interface BroadcastStompSession : StompSession {
    val messages: ReceiveChannel<StompFrame.Message>
    val destinations: StateFlow<ImmutableSet<String>>

    suspend fun subscribeTopic(headers: StompSubscribeHeaders)
    suspend fun unsubscribeTopic(headers: StompUnsubscribeHeaders)
}
