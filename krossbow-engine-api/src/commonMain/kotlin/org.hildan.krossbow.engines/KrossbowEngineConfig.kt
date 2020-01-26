package org.hildan.krossbow.engines

/**
 * Configuration of the STOMP [KrossbowEngineClient].
 */
interface KrossbowEngineConfig {
    /**
     * The heartbeat to use during STOMP sessions.
     */
    val heartBeat: HeartBeat
    /**
     * Whether to automatically attach a `receipt` header to the sent messages in order to track receipts.
     */
    val autoReceipt: Boolean
    /**
     * Defines how long to wait for a RECEIPT frame from the server before throwing a [LostReceiptException].
     * Only crashes when a `receipt` header was actually present in the sent frame (and thus a RECEIPT was expected).
     * Such header is always present if [autoReceipt] is enabled.
     */
    val receiptTimeLimit: Long
}

/**
 * Defines the heart beats for STOMP sessions, as specified in the STOMP specification.
 */
data class HeartBeat(
    /**
     * Represents what the sender of the frame can do (outgoing heart-beats).
     * The value 0 means it cannot send heart-beats, otherwise it is the smallest number of milliseconds between
     * heart-beats that it can guarantee.
     */
    val minSendPeriodMillis: Int = 0,
    /**
     * Represents what the sender of the frame would like to get (incoming heart-beats).
     * The value 0 means it does not want to receive heart-beats, otherwise it is the desired number of milliseconds
     * between heart-beats.
     */
    val expectedPeriodMillis: Int = 0
)