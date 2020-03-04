package org.hildan.krossbow.stomp.frame

import org.hildan.krossbow.stomp.headers.StompMessageHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStdlibApi::class)
class StompParserTest {
    private val nullChar = '\u0000'

    private val frameText1 = """
        MESSAGE
        destination:test
        message-id:123
        subscription:42
        content-length:24
        
        The body of the message.$nullChar
    """.trimIndent()

    private val headers1 = StompMessageHeaders(
        destination = "test",
        messageId = "123",
        subscription = "42",
        customHeaders = mapOf("content-length" to "24")
    )

    data class Expectation(val frameText: String, val expectedTextFrame: StompFrame, val expectedBinFrame: StompFrame)

    private val expectations = listOf(
        Expectation(
            frameText1,
            StompFrame.Message(headers1, FrameBody.Text("The body of the message.")),
            StompFrame.Message(headers1, FrameBody.Binary("The body of the message.".encodeToByteArray()))
        )
    )

    @Test
    fun testParseText() {
        for (e in expectations) {
            val actualFrame = StompParser.parse(e.frameText)
            assertEquals(e.expectedTextFrame, actualFrame)
        }
    }

    @Test
    fun testParseBytes() {
        for (e in expectations) {
            val actualFrame = StompParser.parse(e.frameText.encodeToByteArray())
            assertEquals(e.expectedBinFrame, actualFrame)
        }
    }
}
