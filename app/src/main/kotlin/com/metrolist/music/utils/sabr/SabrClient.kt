package com.metrolist.music.utils.sabr

import android.util.Base64
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * SABR (Server Adaptive Bitrate) streaming client.
 */
object SabrClient {

    private const val TAG = "SabrClient"
    private const val MAX_REQUESTS = 300
    private const val MAX_STALLED_REQUESTS = 5

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class SabrResult(
        val bytesWritten: Long,
        val outputFile: File,
    )

    private class SabrSession(
        var url: String,
        val preferredItag: Int,
        val preferredLmt: Long,
    ) {
        var requestNumber = 0
        var playerTimeMs: Long = 0
        var playbackCookie: ByteArray? = null
        val sabrContextUpdates = mutableMapOf<Int, SabrContextUpdateData>()
        val sabrContextsToSend = mutableSetOf<Int>()
        val initializedFormats = mutableMapOf<String, InitializedFormatState>()
        val partialSegments = mutableMapOf<Int, PartialSegment>()
        var audioFormatKey: String? = null
        var streamComplete = false
        var activityInRequest = false
        var stalledRequests = 0
    }

    private data class SabrContextUpdateData(
        val type: Int,
        val value: ByteArray,
        val sendByDefault: Boolean,
        val writePolicy: Int,
    )

    private class InitializedFormatState(
        val itag: Int,
        val lmt: Long,
        val xtags: String?,
        val discard: Boolean,
        var endTimeMs: Long? = null,
        var totalSegments: Int? = null,
        var mimeType: String? = null,
        var initSegmentReceived: Boolean = false,
        val consumedRanges: MutableList<SabrProtobuf.ConsumedRangeData> = mutableListOf(),
    ) {
        fun toFormatIdData() = SabrProtobuf.FormatIdData(itag, lmt, xtags)
    }

    private data class PartialSegment(
        val formatKey: String,
        val isInitSegment: Boolean,
        val sequenceNumber: Int?,
        val startMs: Long,
        val durationMs: Long,
        val discard: Boolean,
        var receivedBytes: Int = 0,
    )

    fun fetchAudio(
        streamingUrl: String,
        itag: Int,
        lmt: Long,
        durationMs: Long,
        poToken: String?,
        ustreamerConfig: String?,
        outputFile: File,
    ): SabrResult {
        Timber.tag(TAG).d("SABR fetch START: itag=$itag, lmt=$lmt, poToken=${poToken != null}, ustreamerConfig=${ustreamerConfig != null}")

        val poTokenBytes = decodeBase64(poToken)
        val ustreamerBytes = decodeBase64(ustreamerConfig)

        val locale = YouTube.locale
        val session = SabrSession(url = streamingUrl, preferredItag = itag, preferredLmt = lmt)

        outputFile.parentFile?.mkdirs()
        val fos = FileOutputStream(outputFile)
        var totalBytesWritten = 0L

        try {
            while (!session.streamComplete && session.requestNumber < MAX_REQUESTS) {
                session.requestNumber++
                session.activityInRequest = false
                session.partialSegments.clear()

                Timber.tag(TAG).d("── Request #${session.requestNumber}  playerTime=${session.playerTimeMs}ms  formats=${session.initializedFormats.size} ──")

                val body = SabrProtobuf.buildSabrRequestBody(
                    state = buildRequestState(session),
                    preferredAudioFormatId = SabrProtobuf.FormatIdData(itag, lmt),
                    poTokenBytes = poTokenBytes,
                    ustreamerConfig = ustreamerBytes,
                    visitorData = YouTube.visitorData,
                    clientName = SabrProtobuf.CLIENT_NAME_WEB_REMIX,
                    clientVersion = YouTubeClient.WEB_REMIX.clientVersion,
                    userAgent = YouTubeClient.USER_AGENT_WEB,
                    hl = locale.hl,
                    gl = locale.gl,
                )

                val sep = if ("?" in session.url) "&" else "?"
                val requestUrl = "${session.url}${sep}rn=${session.requestNumber}"

                val request = Request.Builder()
                    .url(requestUrl)
                    .post(body.toRequestBody("application/x-protobuf".toMediaType()))
                    .header("Content-Type", "application/x-protobuf")
                    .header("Accept", "application/vnd.yt-ump")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                    .header("Origin", "https://music.youtube.com")
                    .header("Referer", "https://music.youtube.com/")
                    .apply { YouTube.cookie?.let { header("Cookie", it) } }
                    .build()

                val response = try {
                    httpClient.newCall(request).execute()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "HTTP error: ${e.message}")
                    throw SabrException("SABR HTTP request #${session.requestNumber} failed: ${e.message}", e)
                }

                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(500) ?: ""
                    Timber.tag(TAG).e("HTTP ${response.code} body: $errBody")
                    response.close()
                    throw SabrException("SABR HTTP ${response.code}: $errBody")
                }

                val responseStream = response.body?.byteStream()
                    ?: throw SabrException("Empty response body for request #${session.requestNumber}")

                try {
                    val written = processUmpResponse(session, responseStream, fos)
                    totalBytesWritten += written
                    Timber.tag(TAG).d("Request #${session.requestNumber}: +${written}B audio, total=${totalBytesWritten}B")
                } finally {
                    response.close()
                }

                if (session.activityInRequest) {
                    session.stalledRequests = 0
                } else {
                    session.stalledRequests++
                    if (session.stalledRequests >= MAX_STALLED_REQUESTS) {
                        Timber.tag(TAG).d("Stream stalled after $MAX_STALLED_REQUESTS empty requests")
                        break
                    }
                }

                checkEndOfStream(session)

                if (!session.streamComplete) {
                    advancePlayerTime(session)
                }
            }

            fos.flush()
            fos.close()

            if (totalBytesWritten == 0L) {
                outputFile.delete()
                throw SabrException("SABR produced 0 audio bytes after ${session.requestNumber} requests")
            }

            Timber.tag(TAG).d("SABR fetch DONE: ${totalBytesWritten}B in ${session.requestNumber} requests")
            return SabrResult(totalBytesWritten, outputFile)

        } catch (e: Exception) {
            try { fos.close() } catch (_: Exception) {}
            if (totalBytesWritten == 0L) outputFile.delete()
            if (e is SabrException) throw e
            Timber.tag(TAG).e(e, "SABR fetch failed: ${e.message}")
            throw SabrException("SABR fetch failed: ${e.message}", e)
        }
    }

    private fun buildRequestState(session: SabrSession): SabrProtobuf.SabrRequestState {
        val formats = session.initializedFormats.values.map { izf ->
            SabrProtobuf.InitializedFormatData(
                formatId = izf.toFormatIdData(),
                consumedRanges = izf.consumedRanges.toList(),
            )
        }
        val contexts = session.sabrContextsToSend
            .mapNotNull { session.sabrContextUpdates[it] }
            .map { SabrProtobuf.SabrContextData(it.type, it.value) }
        val unsent = session.sabrContextsToSend
            .filter { it !in session.sabrContextUpdates }
            .toList()

        return SabrProtobuf.SabrRequestState(
            playerTimeMs = session.playerTimeMs,
            initializedFormats = formats,
            playbackCookie = session.playbackCookie,
            sabrContexts = contexts,
            unsentSabrContextTypes = unsent,
        )
    }

    private fun processUmpResponse(
        session: SabrSession,
        input: InputStream,
        output: FileOutputStream,
    ): Long {
        var audioBytesWritten = 0L

        for (part in UmpParser.parseParts(input)) {
            when (part.type) {
                UmpParser.PART_FORMAT_INITIALIZATION_METADATA ->
                    processFormatInitMetadata(session, part.payload)
                UmpParser.PART_MEDIA_HEADER ->
                    processMediaHeader(session, part.payload)
                UmpParser.PART_MEDIA ->
                    audioBytesWritten += processMedia(session, part.payload, output)
                UmpParser.PART_MEDIA_END ->
                    processMediaEnd(session, part.payload)
                UmpParser.PART_NEXT_REQUEST_POLICY ->
                    processNextRequestPolicy(session, part.payload)
                UmpParser.PART_SABR_REDIRECT ->
                    processSabrRedirect(session, part.payload)
                UmpParser.PART_SABR_ERROR ->
                    processSabrError(part.payload)
                UmpParser.PART_STREAM_PROTECTION_STATUS ->
                    processStreamProtectionStatus(part.payload)
                UmpParser.PART_SABR_CONTEXT_UPDATE ->
                    processSabrContextUpdate(session, part.payload)
                UmpParser.PART_SABR_CONTEXT_SENDING_POLICY ->
                    processSabrContextSendingPolicy(session, part.payload)
            }
        }
        return audioBytesWritten
    }

    private fun processFormatInitMetadata(session: SabrSession, payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val formatIdFields = with(UmpParser) { fields.getSubmessage(2) } ?: return
        val formatItag = with(UmpParser) { formatIdFields.getVarint(1)?.toInt() } ?: return
        val formatLmt = with(UmpParser) { formatIdFields.getVarint(2) } ?: 0L
        val formatXtags = with(UmpParser) { formatIdFields.getString(3) }
        val endTimeMs = with(UmpParser) { fields.getVarint(3) }
        val totalSegments = with(UmpParser) { fields.getVarint(4)?.toInt() }
        val mimeType = with(UmpParser) { fields.getString(5) }

        val key = "$formatItag"
        if (key in session.initializedFormats) return

        val isAudio = mimeType?.startsWith("audio/") == true
        val discard = !isAudio

        val izf = InitializedFormatState(
            itag = formatItag, lmt = formatLmt, xtags = formatXtags,
            discard = discard, endTimeMs = endTimeMs, totalSegments = totalSegments, mimeType = mimeType,
        )

        if (discard) {
            izf.consumedRanges.add(SabrProtobuf.ConsumedRangeData(
                startSequenceNumber = 0, endSequenceNumber = Int.MAX_VALUE,
                startTimeMs = 0, durationMs = Long.MAX_VALUE / 2,
            ))
        }

        session.initializedFormats[key] = izf
        if (isAudio && session.audioFormatKey == null) {
            session.audioFormatKey = key
        }
        Timber.tag(TAG).d("FormatInit: itag=$formatItag mime=$mimeType discard=$discard")
    }

    private fun processMediaHeader(session: SabrSession, payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val headerId = with(UmpParser) { fields.getVarint(1)?.toInt() } ?: return
        val itag = with(UmpParser) { fields.getVarint(3)?.toInt() }
        val isInitSegment = with(UmpParser) { fields.getBool(8) } ?: false
        val sequenceNumber = with(UmpParser) { fields.getVarint(9)?.toInt() }
        val startMs = with(UmpParser) { fields.getVarint(11) } ?: 0L
        val durationMs = with(UmpParser) { fields.getVarint(12) } ?: 0L

        val fmtFields = with(UmpParser) { fields.getSubmessage(13) }
        val fmtItag = fmtFields?.let { with(UmpParser) { it.getVarint(1)?.toInt() } } ?: itag ?: return

        val key = "$fmtItag"
        val izf = session.initializedFormats[key]
        val discard = izf?.discard ?: true

        session.partialSegments[headerId] = PartialSegment(
            formatKey = key, isInitSegment = isInitSegment, sequenceNumber = sequenceNumber,
            startMs = startMs, durationMs = durationMs, discard = discard,
        )
    }

    private fun processMedia(session: SabrSession, payload: ByteArray, output: FileOutputStream): Long {
        if (payload.isEmpty()) return 0
        val payloadStream = ByteArrayInputStream(payload)
        val headerId = UmpParser.readVarInt(payloadStream)
        if (headerId == -1L) return 0

        val dataOffset = payload.size - payloadStream.available()
        val segment = session.partialSegments[headerId.toInt()] ?: return 0

        val dataLength = payload.size - dataOffset
        segment.receivedBytes += dataLength

        if (!segment.discard && dataLength > 0) {
            output.write(payload, dataOffset, dataLength)
            return dataLength.toLong()
        }
        return 0
    }

    private fun processMediaEnd(session: SabrSession, payload: ByteArray) {
        if (payload.isEmpty()) return
        val payloadStream = ByteArrayInputStream(payload)
        val headerId = UmpParser.readVarInt(payloadStream)
        if (headerId == -1L) return

        val segment = session.partialSegments.remove(headerId.toInt()) ?: return
        val izf = session.initializedFormats[segment.formatKey] ?: return

        if (!segment.discard) {
            session.activityInRequest = true
        }

        if (segment.isInitSegment) {
            izf.initSegmentReceived = true
            return
        }

        val seqNum = segment.sequenceNumber ?: return

        val existing = izf.consumedRanges.indexOfFirst { it.endSequenceNumber == seqNum - 1 }
        if (existing >= 0) {
            val old = izf.consumedRanges[existing]
            izf.consumedRanges[existing] = SabrProtobuf.ConsumedRangeData(
                startSequenceNumber = old.startSequenceNumber, endSequenceNumber = seqNum,
                startTimeMs = old.startTimeMs, durationMs = (segment.startMs - old.startTimeMs) + segment.durationMs,
            )
        } else {
            izf.consumedRanges.add(SabrProtobuf.ConsumedRangeData(
                startSequenceNumber = seqNum, endSequenceNumber = seqNum,
                startTimeMs = segment.startMs, durationMs = segment.durationMs,
            ))
        }
    }

    private fun processNextRequestPolicy(session: SabrSession, payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val cookie = with(UmpParser) { fields.getBytes(7) }
        if (cookie != null) {
            session.playbackCookie = cookie
        }
    }

    private fun processSabrRedirect(session: SabrSession, payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val redirectUrl = with(UmpParser) { fields.getString(1) }
        if (redirectUrl != null) {
            Timber.tag(TAG).d("SABR redirect: ${redirectUrl.take(80)}...")
            session.url = redirectUrl
        }
    }

    private fun processSabrError(payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val errorType = with(UmpParser) { fields.getString(1) }
        val errorAction = with(UmpParser) { fields.getVarint(2) }
        val errorSub = with(UmpParser) { fields.getSubmessage(3) }
        val statusCode = errorSub?.let { with(UmpParser) { it.getVarint(1) } }
        Timber.tag(TAG).e("SABR error: type='$errorType' action=$errorAction statusCode=$statusCode")
        throw SabrException("SABR protocol error: type='$errorType' action=$errorAction statusCode=$statusCode")
    }

    private fun processStreamProtectionStatus(payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val status = with(UmpParser) { fields.getVarint(1)?.toInt() } ?: 0
        val statusName = when (status) {
            1 -> "OK"
            2 -> "ATTESTATION_PENDING"
            3 -> "ATTESTATION_REQUIRED"
            else -> "UNKNOWN($status)"
        }
        Timber.tag(TAG).d("StreamProtectionStatus: $statusName")
        if (status == 3) {
            throw SabrException("Stream protection ATTESTATION_REQUIRED — poToken may be invalid or missing")
        }
    }

    private fun processSabrContextUpdate(session: SabrSession, payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        val type = with(UmpParser) { fields.getVarint(1)?.toInt() } ?: return
        val value = with(UmpParser) { fields.getBytes(3) } ?: return
        val sendByDefault = with(UmpParser) { fields.getBool(4) } ?: false
        val writePolicy = with(UmpParser) { fields.getVarint(5)?.toInt() } ?: 0

        if (writePolicy == 2 && type in session.sabrContextUpdates) return

        session.sabrContextUpdates[type] = SabrContextUpdateData(type, value, sendByDefault, writePolicy)
        if (sendByDefault) session.sabrContextsToSend.add(type)
    }

    private fun processSabrContextSendingPolicy(session: SabrSession, payload: ByteArray) {
        val fields = UmpParser.parseProtoFields(payload)
        for (t in with(UmpParser) { fields.getAllVarints(1) }) session.sabrContextsToSend.add(t.toInt())
        for (t in with(UmpParser) { fields.getAllVarints(2) }) session.sabrContextsToSend.remove(t.toInt())
        for (t in with(UmpParser) { fields.getAllVarints(3) }) session.sabrContextUpdates.remove(t.toInt())
    }

    private fun checkEndOfStream(session: SabrSession) {
        val audioIzf = session.audioFormatKey?.let { session.initializedFormats[it] } ?: return
        val activeRanges = audioIzf.consumedRanges.filter { it.endSequenceNumber < Int.MAX_VALUE }

        val totalSegs = audioIzf.totalSegments
        if (totalSegs != null && totalSegs > 0) {
            val maxSeq = activeRanges.maxOfOrNull { it.endSequenceNumber }
            if (maxSeq != null && maxSeq >= totalSegs) {
                session.streamComplete = true
                return
            }
        }

        val endTimeMs = audioIzf.endTimeMs
        if (endTimeMs != null && endTimeMs > 0 && session.playerTimeMs >= endTimeMs) {
            session.streamComplete = true
        }
    }

    private fun advancePlayerTime(session: SabrSession) {
        val audioIzf = session.audioFormatKey?.let { session.initializedFormats[it] } ?: return
        val activeRanges = audioIzf.consumedRanges.filter { it.endSequenceNumber < Int.MAX_VALUE }
        if (activeRanges.isEmpty()) return

        val coveringRange = activeRanges.find { cr ->
            session.playerTimeMs >= cr.startTimeMs &&
                session.playerTimeMs < cr.startTimeMs + cr.durationMs
        }

        val newTime = if (coveringRange != null) {
            coveringRange.startTimeMs + coveringRange.durationMs
        } else {
            activeRanges.maxOf { it.startTimeMs + it.durationMs }
        }

        if (newTime > session.playerTimeMs) {
            session.playerTimeMs = newTime
        }
    }

    private fun decodeBase64(value: String?): ByteArray? {
        if (value == null) return null
        return try {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            try { Base64.decode(value, Base64.DEFAULT) } catch (_: Exception) { null }
        }
    }
}
