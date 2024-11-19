package org.hildan.krossbow.stomp.session.broadcast

import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import org.hildan.krossbow.stomp.headers.StompUnsubscribeHeaders

sealed interface BroadcastTopicRequest {
    data class Subscribe(
        val headers: StompSubscribeHeaders
    ) : BroadcastTopicRequest

    data class Unsubscribe(
        val headers: StompUnsubscribeHeaders
    ) : BroadcastTopicRequest
}
