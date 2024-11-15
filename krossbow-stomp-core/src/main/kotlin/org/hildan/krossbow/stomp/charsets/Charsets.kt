package org.hildan.krossbow.stomp.charsets

import kotlinx.io.bytestring.*
import kotlin.jvm.*

private val utf8Names = setOf("utf-8", "UTF-8", "utf8", "UTF8")

@JvmInline
internal value class Charset private constructor(val name: String) {

    companion object {
        val UTF_8: Charset = Charset("UTF-8")

        // we do this mapping to make the comparison with the UTF-8 constant possible (and easy)
        fun forName(name: String): Charset = if (name in utf8Names) UTF_8 else Charset(name)
    }
}

internal  fun String.encodeToByteString(charset: Charset): ByteString = when (charset) {
    Charset.UTF_8 -> encodeToByteString()
    else -> error("Charset $charset is not supported on native platforms, please use UTF-8")
}

internal  fun ByteString.decodeToString(charset: Charset): String = when (charset) {
    Charset.UTF_8 -> decodeToString()
    else -> error("Charset $charset is not supported on native platforms, please use UTF-8")
}


internal fun extractCharset(mimeTypeText: String): Charset? = mimeTypeText.splitToSequence(';')
    .drop(1)
    .map { it.substringAfter("charset=", "") }
    .firstOrNull { it.isNotEmpty() }
    ?.let { Charset.forName(it) }
