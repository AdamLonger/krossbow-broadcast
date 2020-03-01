package org.hildan.krossbow.stomp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.StompCommand
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.StompReceiptHeaders
import org.hildan.krossbow.stomp.headers.StompSendHeaders
import org.hildan.krossbow.test.ImmediatelySucceedingWebSocketClient
import org.hildan.krossbow.test.WebSocketSessionMock
import org.hildan.krossbow.test.runAsyncTestWithTimeout
import org.hildan.krossbow.test.simulateConnectedFrameReceived
import org.hildan.krossbow.test.simulateTextStompFrameReceived
import org.hildan.krossbow.test.waitAndAssertSentFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StompSessionTest {

    private suspend fun connectToMock(
        configure: StompConfig.() -> Unit = {}
    ): Pair<WebSocketSessionMock, StompSession> = coroutineScope {
        val wsSession = WebSocketSessionMock()
        val stompClient = StompClient(ImmediatelySucceedingWebSocketClient(wsSession), configure)
        val session = async { stompClient.connect("dummy URL") }
        wsSession.waitAndAssertSentFrame(StompCommand.CONNECT)
        wsSession.simulateConnectedFrameReceived()
        val stompSession = session.await()
        Pair(wsSession, stompSession)
    }

    @Test
    fun send_doesntWaitIfNoReceipt() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectToMock()
        val receipt = async {
            stompSession.send("/destination")
        }
        assertFalse(receipt.isCompleted)
        wsSession.waitAndAssertSentFrame(StompCommand.SEND)
        assertTrue(receipt.isCompleted)
        assertNull(receipt.await())
    }

    @Test
    fun send_waitsIfAutoReceipt() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectToMock {
            autoReceipt = true
        }
        val receipt = async {
            stompSession.send("/destination")
        }
        assertFalse(receipt.isCompleted)
        val sendFrame = wsSession.waitAndAssertSentFrame(StompCommand.SEND)
        val receiptId = sendFrame.headers.receipt
        assertNotNull(receiptId, "receipt header should be auto-populated")
        assertFalse(receipt.isCompleted, "send() should wait until receipt is received")
        wsSession.simulateTextStompFrameReceived(StompFrame.Receipt(StompReceiptHeaders(receiptId)))
        delay(10)
        assertTrue(receipt.isCompleted, "send() should resume after correct receipt")
        assertEquals(StompReceipt(receiptId), receipt.await())
    }

    @Test
    fun send_waitsForCorrectReceipt() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectToMock()
        val manualReceiptId = "my-receipt"
        val receipt = async {
            val headers = StompSendHeaders(destination = "/destination")
            headers.receipt = manualReceiptId
            stompSession.send(headers, null)
        }
        assertFalse(receipt.isCompleted, "send() should wait until ws send finishes")
        wsSession.waitAndAssertSentFrame(StompCommand.SEND)
        assertFalse(receipt.isCompleted, "send() should wait until receipt is received")
        wsSession.simulateTextStompFrameReceived(StompFrame.Receipt(StompReceiptHeaders("other-receipt")))
        assertFalse(receipt.isCompleted, "send() should not resume on other receipts")
        wsSession.simulateTextStompFrameReceived(StompFrame.Receipt(StompReceiptHeaders(manualReceiptId)))
        delay(10)
        assertTrue(receipt.isCompleted, "send() should resume after correct receipt")
        assertEquals(StompReceipt(manualReceiptId), receipt.await())
    }
}
