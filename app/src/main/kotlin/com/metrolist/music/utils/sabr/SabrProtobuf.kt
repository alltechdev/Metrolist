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
        out.writeTag(1, WIRE_LENGTH_DELIMITED)
        out.writeBytes(clientAbrState)

        // field 2: initialized_format_ids (repeated)
        for (fmt in state.initializedFormats) {
            val fmtBytes = buildFormatId(fmt.formatId)
            out.writeTag(2, WIRE_LENGTH_DELIMITED)
            out.writeBytes(fmtBytes)
        }

        // field 3: buffered_ranges (repeated)
        for (fmt in state.initializedFormats) {
            for (cr in fmt.consumedRanges) {
                val brBytes = buildBufferedRange(fmt.formatId, cr)
                out.writeTag(3, WIRE_LENGTH_DELIMITED)
                out.writeBytes(brBytes)
            }
        }

        // field 5: video_playback_ustreamer_config
        if (ustreamerConfig != null && ustreamerConfig.isNotEmpty()) {
            out.writeTag(5, WIRE_LENGTH_DELIMITED)
            out.writeBytes(ustreamerConfig)
        }

        // field 16: preferred_audio_format_ids
        val formatIdBytes = buildFormatId(preferredAudioFormatId)
        out.writeTag(16, WIRE_LENGTH_DELIMITED)
        out.writeBytes(formatIdBytes)

        // field 19: streamer_context
        val streamerContext = buildStreamerContext(
            poTokenBytes, state.playbackCookie,
            state.sabrContexts, state.unsentSabrContextTypes,
            visitorData, clientName, clientVersion, userAgent, hl, gl
        )
        out.writeTag(19, WIRE_LENGTH_DELIMITED)
        out.writeBytes(streamerContext)

        return out.toByteArray()
    }

    private fun buildClientAbrState(playerTimeMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeTag(28, WIRE_VARINT)
        out.writeVarint(playerTimeMs)
        out.writeTag(40, WIRE_VARINT)
        out.writeVarint(1L)
        out.writeTag(46, WIRE_VARINT)
        out.writeVarint(1L)
        out.writeTag(76, WIRE_VARINT)
        out.writeVarint(1L)
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
        out.writeTag(1, WIRE_LENGTH_DELIMITED)
        out.writeBytes(clientInfo)

        if (poTokenBytes != null && poTokenBytes.isNotEmpty()) {
            out.writeTag(2, WIRE_LENGTH_DELIMITED)
            out.writeBytes(poTokenBytes)
        }

        if (playbackCookie != null && playbackCookie.isNotEmpty()) {
            out.writeTag(3, WIRE_LENGTH_DELIMITED)
            out.writeBytes(playbackCookie)
        }

        for (ctx in sabrContexts) {
            val ctxBytes = buildSabrContext(ctx)
            out.writeTag(5, WIRE_LENGTH_DELIMITED)
            out.writeBytes(ctxBytes)
        }

        for (type in unsentSabrContextTypes) {
            out.writeTag(6, WIRE_VARINT)
            out.writeVarint(type.toLong())
        }

        return out.toByteArray()
    }

    private fun buildSabrContext(ctx: SabrContextData): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeTag(1, WIRE_VARINT)
        out.writeVarint(ctx.type.toLong())
        out.writeTag(2, WIRE_LENGTH_DELIMITED)
        out.writeBytes(ctx.value)
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
        out.writeTag(16, WIRE_VARINT)
        out.writeVarint(clientName.toLong())
        if (clientVersion != null) {
            out.writeTag(17, WIRE_LENGTH_DELIMITED)
            out.writeBytes(clientVersion.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun buildFormatId(fid: FormatIdData): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeTag(1, WIRE_VARINT)
        out.writeVarint(fid.itag.toLong())
        if (fid.lmt > 0) {
            out.writeTag(2, WIRE_VARINT)
            out.writeVarint(fid.lmt)
        }
        if (fid.xtags != null) {
            out.writeTag(3, WIRE_LENGTH_DELIMITED)
            out.writeBytes(fid.xtags.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun buildBufferedRange(formatId: FormatIdData, cr: ConsumedRangeData): ByteArray {
        val out = ByteArrayOutputStream()
        val fmtBytes = buildFormatId(formatId)
        out.writeTag(1, WIRE_LENGTH_DELIMITED)
        out.writeBytes(fmtBytes)
        out.writeTag(2, WIRE_VARINT)
        out.writeVarint(cr.startTimeMs)
        out.writeTag(3, WIRE_VARINT)
        out.writeVarint(cr.durationMs)
        out.writeTag(4, WIRE_VARINT)
        out.writeVarint(cr.startSequenceNumber.toLong())
        out.writeTag(5, WIRE_VARINT)
        out.writeVarint(cr.endSequenceNumber.toLong())
        val trBytes = buildTimeRange(cr.startTimeMs, cr.durationMs)
        out.writeTag(6, WIRE_LENGTH_DELIMITED)
        out.writeBytes(trBytes)
        return out.toByteArray()
    }

    private fun buildTimeRange(startTicks: Long, durationTicks: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeTag(1, WIRE_VARINT)
        out.writeVarint(startTicks)
        out.writeTag(2, WIRE_VARINT)
        out.writeVarint(durationTicks)
        out.writeTag(3, WIRE_VARINT)
        out.writeVarint(1000L)
        return out.toByteArray()
    }

    // Standard protobuf encoding helpers
    private fun ByteArrayOutputStream.writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                write(v.toInt())
                return
            }
            write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun ByteArrayOutputStream.writeBytes(data: ByteArray) {
        writeVarint(data.size.toLong())
        write(data)
    }
}
