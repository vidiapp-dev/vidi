package com.example.ytclient.player.sabr

/**
 * One UMP part: a numeric type tag (see [UmpPartType]) and its raw payload.
 * The payload is decoded into a protobuf message by the SABR client depending
 * on [type] (e.g. a MEDIA_HEADER payload → `MediaHeader`).
 */
class UmpPart(val type: Int, val data: ByteArray)

/**
 * Parser for YouTube's **UMP** framing (the wire format SABR responses use).
 *
 * A response body is a flat sequence of parts, each laid out as:
 * ```
 * [ partType : UMP-varint ][ payloadSize : UMP-varint ][ payload : payloadSize bytes ]
 * ```
 *
 * UMP varints are **not** protobuf varints. The number of leading 1-bits in the
 * first byte determines the total byte length (1..5); the value is assembled
 * little-endian from the remaining low bits of the first byte plus the following
 * bytes. A 5-byte varint ignores the first byte's data bits and takes the next
 * four bytes as a uint32 LE. (Mirrors LuanRT/googlevideo `src/core/Ump.ts`.)
 *
 * This parser operates on a complete response buffer; SABR returns one bounded
 * response per request, which the client reads fully before parsing. A
 * truncated trailing part is skipped rather than throwing.
 */
object UmpReader {

    fun parse(buf: ByteArray): List<UmpPart> {
        val parts = ArrayList<UmpPart>()
        var pos = 0
        val n = buf.size
        while (pos < n) {
            val typeRead = readVarint(buf, pos) ?: break
            val sizeRead = readVarint(buf, typeRead.second) ?: break
            val payloadStart = sizeRead.second
            val size = sizeRead.first.toInt()
            if (size < 0 || payloadStart + size > n) break // incomplete/truncated tail
            parts.add(UmpPart(typeRead.first.toInt(), buf.copyOfRange(payloadStart, payloadStart + size)))
            pos = payloadStart + size
        }
        return parts
    }

    /**
     * Reads one UMP varint at [pos]; returns (value, nextPos) or null if there
     * aren't enough bytes. Public because MEDIA part payloads begin with a
     * UMP-varint `header_id` that the decoder must read.
     */
    fun readVarint(buf: ByteArray, pos: Int): Pair<Long, Int>? {
        if (pos >= buf.size) return null
        val b0 = buf[pos].toInt() and 0xFF
        val size = varintSize(b0)
        if (pos + size > buf.size) return null

        fun byte(offset: Int): Long = (buf[pos + offset].toInt() and 0xFF).toLong()

        val value: Long = when (size) {
            1 -> b0.toLong()
            2 -> (byte(1) shl 6) or (b0 and 0x3f).toLong()
            3 -> (byte(2) shl 13) or (byte(1) shl 5) or (b0 and 0x1f).toLong()
            4 -> (byte(3) shl 20) or (byte(2) shl 12) or (byte(1) shl 4) or (b0 and 0x0f).toLong()
            else -> byte(1) or (byte(2) shl 8) or (byte(3) shl 16) or (byte(4) shl 24) // size 5: uint32 LE
        }
        return value to (pos + size)
    }

    /** Total byte length of a UMP varint from the leading 1-bits of [firstByte]. */
    private fun varintSize(firstByte: Int): Int {
        var size = 1
        if (firstByte and 0x80 != 0) {
            size++
            if (firstByte and 0x40 != 0) {
                size++
                if (firstByte and 0x20 != 0) {
                    size++
                    if (firstByte and 0x10 != 0) size++
                }
            }
        }
        return size
    }
}

/**
 * UMP part type IDs we handle, from `video_streaming/ump_part_id.proto`
 * (`UMPPartId`). Kept as plain constants so this layer doesn't depend on the
 * generated proto enum's nested naming.
 */
object UmpPartType {
    const val MEDIA_HEADER = 20
    const val MEDIA = 21
    const val MEDIA_END = 22
    const val NEXT_REQUEST_POLICY = 35
    const val FORMAT_INITIALIZATION_METADATA = 42
    const val SABR_REDIRECT = 43
    const val SABR_ERROR = 44
    const val RELOAD_PLAYER_RESPONSE = 46
    const val SABR_CONTEXT_UPDATE = 57
    const val STREAM_PROTECTION_STATUS = 58
    const val SABR_CONTEXT_SENDING_POLICY = 59
    const val SNACKBAR_MESSAGE = 67
}
