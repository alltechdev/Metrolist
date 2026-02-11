package com.metrolist.music.utils.sabr

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream

object UmpParser {

    private const val TAG = "Zemer_UmpParser"

    const val PART_MEDIA_HEADER = 20
    const val PART_MEDIA = 21
    const val PART_MEDIA_END = 22
    const val PART_LIVE_METADATA = 31
    const val PART_NEXT_REQUEST_POLICY = 35
    const val PART_FORMAT_INITIALIZATION_METADATA = 42
    const val PART_SABR_REDIRECT = 43
    const val PART_SABR_ERROR = 44
    const val PART_SABR_SEEK = 45
    const val PART_SABR_CONTEXT_UPDATE = 57
    const val PART_STREAM_PROTECTION_STATUS = 58
    const val PART_SABR_CONTEXT_SENDING_POLICY = 59

    data class UmpPart(val type: Int, val payload: ByteArray)

    fun readVarInt(input: InputStream): Long {
        val first = input.read()
        if (first == -1) return -1L

        val prefix = first and 0xFF
        val size = varintSize(prefix)

        var result = 0L
        var shift = 0

        if (size != 5) {
            shift = 8 - size
            val mask = (1 shl shift) - 1
            result = (prefix and mask).toLong()
        }

        for (i in 1 until size) {
            val b = input.read()
            if (b == -1) throw SabrException("Unexpected EOF in $size-byte UMP varint")
            result = result or ((b.toLong() and 0xFF) shl shift)
            shift += 8
        }

        return result
    }

    private fun varintSize(prefix: Int): Int = when {
        prefix < 128 -> 1
        prefix < 192 -> 2
        prefix < 224 -> 3
        prefix < 240 -> 4
        else -> 5
    }

    fun writeVarInt(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "Value must be non-negative" }
        when {
            value < 128 -> {
                out.write(value.toInt())
            }
            value < 16384 -> {
                out.write(((value.toInt() and 0x3F) or 0x80))
                out.write((value shr 6).toInt())
            }
            value < 2097152 -> {
                out.write(((value.toInt() and 0x1F) or 0xC0))
                out.write(((value shr 5).toInt() and 0xFF))
                out.write((value shr 13).toInt())
            }
            value < 268435456 -> {
                out.write(((value.toInt() and 0x0F) or 0xE0))
                out.write(((value shr 4).toInt() and 0xFF))
                out.write(((value shr 12).toInt() and 0xFF))
                out.write((value shr 20).toInt())
            }
            else -> {
                out.write(0xF0)
                out.write((value.toInt() and 0xFF))
                out.write(((value shr 8).toInt() and 0xFF))
                out.write(((value shr 16).toInt() and 0xFF))
                out.write(((value shr 24).toInt() and 0xFF))
            }
        }
    }

    fun parseParts(input: InputStream): Sequence<UmpPart> = sequence {
        while (true) {
            val typeId = readVarInt(input)
            if (typeId == -1L) break

            val payloadSize = readVarInt(input)
            if (payloadSize == -1L) throw SabrException("Unexpected EOF reading payload size for part type $typeId")

            val payload = ByteArray(payloadSize.toInt())
            var read = 0
            while (read < payloadSize.toInt()) {
                val n = input.read(payload, read, payloadSize.toInt() - read)
                if (n == -1) throw SabrException("Unexpected EOF reading payload for part type $typeId (read $read of $payloadSize)")
                read += n
            }

            yield(UmpPart(typeId.toInt(), payload))
        }
    }

    fun readProtobufVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            pos++
            if ((b and 0x80) == 0L) {
                return result to pos
            }
            shift += 7
            if (shift >= 64) throw SabrException("Protobuf varint too long")
        }
        throw SabrException("Unexpected end of protobuf varint")
    }

    fun readProtobufVarintFromStream(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = input.read()
            if (byte == -1) return -1L
            result = result or (((byte.toLong()) and 0x7F) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
            if (shift >= 64) throw SabrException("Protobuf varint too long")
        }
    }

    data class ProtoField(
        val wireType: Int,
        val varintValue: Long = 0,
        val bytesValue: ByteArray = ByteArray(0),
    )

    fun parseProtoFields(data: ByteArray): Map<Int, List<ProtoField>> {
        val fields = mutableMapOf<Int, MutableList<ProtoField>>()
        var offset = 0
        while (offset < data.size) {
            val (tag, newOffset) = readProtobufVarint(data, offset)
            offset = newOffset
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (wireType) {
                0 -> { // Varint
                    val (value, nextOffset) = readProtobufVarint(data, offset)
                    offset = nextOffset
                    fields.getOrPut(fieldNumber) { mutableListOf() }
                        .add(ProtoField(wireType = 0, varintValue = value))
                }
                1 -> { // 64-bit fixed
                    if (offset + 8 > data.size) break
                    var value = 0L
                    for (i in 0 until 8) {
                        value = value or ((data[offset + i].toLong() and 0xFF) shl (i * 8))
                    }
                    offset += 8
                    fields.getOrPut(fieldNumber) { mutableListOf() }
                        .add(ProtoField(wireType = 1, varintValue = value))
                }
                2 -> { // Length-delimited
                    val (length, nextOffset) = readProtobufVarint(data, offset)
                    offset = nextOffset
                    val len = length.toInt()
                    if (offset + len > data.size) break
                    val bytes = data.copyOfRange(offset, offset + len)
                    offset += len
                    fields.getOrPut(fieldNumber) { mutableListOf() }
                        .add(ProtoField(wireType = 2, bytesValue = bytes))
                }
                5 -> { // 32-bit fixed
                    if (offset + 4 > data.size) break
                    var value = 0L
                    for (i in 0 until 4) {
                        value = value or ((data[offset + i].toLong() and 0xFF) shl (i * 8))
                    }
                    offset += 4
                    fields.getOrPut(fieldNumber) { mutableListOf() }
                        .add(ProtoField(wireType = 5, varintValue = value))
                }
                else -> {
                    break
                }
            }
        }
        return fields
    }

    // Helper extensions for reading proto fields
    fun Map<Int, List<ProtoField>>.getVarint(fieldNumber: Int): Long? =
        get(fieldNumber)?.firstOrNull()?.varintValue

    fun Map<Int, List<ProtoField>>.getString(fieldNumber: Int): String? =
        get(fieldNumber)?.firstOrNull()?.bytesValue?.let { String(it, Charsets.UTF_8) }

    fun Map<Int, List<ProtoField>>.getBytes(fieldNumber: Int): ByteArray? =
        get(fieldNumber)?.firstOrNull()?.bytesValue

    fun Map<Int, List<ProtoField>>.getBool(fieldNumber: Int): Boolean? =
        get(fieldNumber)?.firstOrNull()?.let { it.varintValue != 0L }

    fun Map<Int, List<ProtoField>>.getSubmessage(fieldNumber: Int): Map<Int, List<ProtoField>>? =
        get(fieldNumber)?.firstOrNull()?.bytesValue?.let { parseProtoFields(it) }

    fun Map<Int, List<ProtoField>>.getAllSubmessages(fieldNumber: Int): List<Map<Int, List<ProtoField>>> =
        get(fieldNumber)?.map { parseProtoFields(it.bytesValue) } ?: emptyList()

    fun Map<Int, List<ProtoField>>.getAllVarints(fieldNumber: Int): List<Long> =
        get(fieldNumber)?.map { it.varintValue } ?: emptyList()
}
