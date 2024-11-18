package org.hildan.krossbow.stomp.session

data class StompReceipt(
    /**
     * The value of the receipt header sent to the server, and returned in a RECEIPT frame.
     */
    val id: String
)
