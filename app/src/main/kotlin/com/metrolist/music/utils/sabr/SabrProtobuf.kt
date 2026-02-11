package com.metrolist.music.utils.sabr

import java.io.ByteArrayOutputStream

/**
 * Protobuf wire-format encoder for SABR VideoPlaybackAbrRequest.
 */
object SabrProtobuf {

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    const val CLIENT_NAME_WEB_REMIX = 67

    data class FormatIdData(
        val itag: Int,
        val lmt: Long = 0,
        val xtags: String? = null,
    )

    data class ConsumedRangeData(
        val startSequenceNumber: Int,
        val endSequenceNumber: Int,
        val startTimeMs: Long,
        val durationMs: Long,
    )

    data class InitializedFormatData(
        val formatId: FormatIdData,
        val consumedRanges: List<ConsumedRangeData> = emptyList(),
    )

    data class SabrContextData(
        val type: Int,
        val value: ByteArray,
    )

    data class SabrRequestState(
        val playerTimeMs: Long = 0,
        val initializedFormats: List<InitializedFormatData> = emptyList(),
        val playbackCookie: ByteArray? = null,
        val sabrContexts: List<SabrContextData> = emptyList(),
        val unsentSabrContextTypes: List<Int> = emptyList(),
    )

    fun buildSabrRequestBody(
        state: SabrRequestState,
        preferredAudioFormatId: FormatIdData,
        poTokenBytes: ByteArray?,
        ustreamerConfig: ByteArray?,
        visitorData: String? = null,
        clientName: Int = CLIENT_NAME_WEB_REMIX,
        clientVersion: String? = null,
        userAgent: String? = null,
        hl: String? = null,
        gl: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // field 1: client_abr_state
        val clientAbrState = buildClientAbrState(state.playerTimeMs)
        writeTag(out, 1, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, clientAbrState)

        // field 2: initialized_format_ids (repeated)
        for (fmt in state.initializedFormats) {
            val fmtBytes = buildFormatId(fmt.formatId)
            writeTag(out, 2, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, fmtBytes)
        }

        // field 3: buffered_ranges (repeated)
        for (fmt in state.initializedFormats) {
            for (cr in fmt.consumedRanges) {
                val brBytes = buildBufferedRange(fmt.formatId, cr)
                writeTag(out, 3, WIRE_LENGTH_DELIMITED)
                writeLengthDelimited(out, brBytes)
            }
        }

        // field 5: video_playback_ustreamer_config
        if (ustreamerConfig != null && ustreamerConfig.isNotEmpty()) {
            writeTag(out, 5, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, ustreamerConfig)
        }

        // field 16: preferred_audio_format_ids
        val formatIdBytes = buildFormatId(preferredAudioFormatId)
        writeTag(out, 16, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, formatIdBytes)

        // field 19: streamer_context
        val streamerContext = buildStreamerContext(
            poTokenBytes, state.playbackCookie,
            state.sabrContexts, state.unsentSabrContextTypes,
            visitorData, clientName, clientVersion, userAgent, hl, gl
        )
        writeTag(out, 19, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, streamerContext)

        return out.toByteArray()
    }

    private fun buildClientAbrState(playerTimeMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 28, WIRE_VARINT)
        writeVarint(out, playerTimeMs)
        writeTag(out, 40, WIRE_VARINT)
        writeVarint(out, 1L)
        writeTag(out, 46, WIRE_VARINT)
        writeVarint(out, 1L)
        writeTag(out, 76, WIRE_VARINT)
        writeVarint(out, 1L)
        return out.toByteArray()
    }

    private fun buildStreamerContext(
        poTokenBytes: ByteArray?,
        playbackCookie: ByteArray?,
        sabrContexts: List<SabrContextData>,
        unsentSabrContextTypes: List<Int>,
        visitorData: String?,
        clientName: Int,
        clientVersion: String?,
        userAgent: String?,
        hl: String?,
        gl: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        val clientInfo = buildClientInfo(visitorData, clientName, clientVersion, userAgent, hl, gl)
        writeTag(out, 1, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, clientInfo)

        if (poTokenBytes != null && poTokenBytes.isNotEmpty()) {
            writeTag(out, 2, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, poTokenBytes)
        }

        if (playbackCookie != null && playbackCookie.isNotEmpty()) {
            writeTag(out, 3, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, playbackCookie)
        }

        for (ctx in sabrContexts) {
            val ctxBytes = buildSabrContext(ctx)
            writeTag(out, 5, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, ctxBytes)
        }

        for (type in unsentSabrContextTypes) {
            writeTag(out, 6, WIRE_VARINT)
            writeVarint(out, type.toLong())
        }

        return out.toByteArray()
    }

    private fun buildSabrContext(ctx: SabrContextData): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 1, WIRE_VARINT)
        writeVarint(out, ctx.type.toLong())
        writeTag(out, 2, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, ctx.value)
        return out.toByteArray()
    }

    private fun buildClientInfo(
        visitorData: String?,
        clientName: Int,
        clientVersion: String?,
        userAgent: String?,
        hl: String?,
        gl: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (hl != null) {
            writeTag(out, 1, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, hl.toByteArray(Charsets.UTF_8))
        }
        if (gl != null) {
            writeTag(out, 2, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, gl.toByteArray(Charsets.UTF_8))
        }
        if (visitorData != null) {
            writeTag(out, 14, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, visitorData.toByteArray(Charsets.UTF_8))
        }
        if (userAgent != null) {
            writeTag(out, 15, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, userAgent.toByteArray(Charsets.UTF_8))
        }
        writeTag(out, 16, WIRE_VARINT)
        writeVarint(out, clientName.toLong())
        if (clientVersion != null) {
            writeTag(out, 17, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, clientVersion.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun buildFormatId(fid: FormatIdData): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 1, WIRE_VARINT)
        writeVarint(out, fid.itag.toLong())
        if (fid.lmt > 0) {
            writeTag(out, 2, WIRE_VARINT)
            writeVarint(out, fid.lmt)
        }
        if (fid.xtags != null) {
            writeTag(out, 3, WIRE_LENGTH_DELIMITED)
            writeLengthDelimited(out, fid.xtags.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun buildBufferedRange(formatId: FormatIdData, cr: ConsumedRangeData): ByteArray {
        val out = ByteArrayOutputStream()
        val fmtBytes = buildFormatId(formatId)
        writeTag(out, 1, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, fmtBytes)
        writeTag(out, 2, WIRE_VARINT)
        writeVarint(out, cr.startTimeMs)
        writeTag(out, 3, WIRE_VARINT)
        writeVarint(out, cr.durationMs)
        writeTag(out, 4, WIRE_VARINT)
        writeVarint(out, cr.startSequenceNumber.toLong())
        writeTag(out, 5, WIRE_VARINT)
        writeVarint(out, cr.endSequenceNumber.toLong())
        val trBytes = buildTimeRange(cr.startTimeMs, cr.durationMs)
        writeTag(out, 6, WIRE_LENGTH_DELIMITED)
        writeLengthDelimited(out, trBytes)
        return out.toByteArray()
    }

    private fun buildTimeRange(startTicks: Long, durationTicks: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 1, WIRE_VARINT)
        writeVarint(out, startTicks)
        writeTag(out, 2, WIRE_VARINT)
        writeVarint(out, durationTicks)
        writeTag(out, 3, WIRE_VARINT)
        writeVarint(out, 1000L)
        return out.toByteArray()
    }

    // Standard protobuf encoding helpers
    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun writeLengthDelimited(out: ByteArrayOutputStream, data: ByteArray) {
        writeVarint(out, data.size.toLong())
        out.write(data)
    }
}
