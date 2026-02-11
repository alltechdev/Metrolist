/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val TAG = "Zemer_YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
        WEB,
        WEB_CREATOR
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val isSabr: Boolean = false,
        val sabrStreamingUrl: String? = null,
        val sabrItag: Int? = null,
        val sabrLmt: Long? = null,
        val streamingPoToken: String? = null,
        val ustreamerConfig: String? = null,
    )

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(TAG).d("=== Stream resolution START for videoId=$videoId ===")

        val defaultStreamTtlSeconds = 6 * 60 * 60 // 6 hours
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(TAG).d("Signature timestamp: ${signatureTimestamp ?: "FAILED/null"}")

        // Enhanced authentication validation with SAPISID check
        val currentAuthCookie = YouTube.cookie
        val isLoggedIn = currentAuthCookie != null && "SAPISID" in parseCookieString(currentAuthCookie)
        Timber.tag(TAG).d("Auth: isLoggedIn=$isLoggedIn")

        val sessionId = if (isLoggedIn) {
            YouTube.dataSyncId ?: YouTube.visitorData
        } else {
            YouTube.visitorData
        }

        // Generate PoToken for web clients
        val poTokenResult: PoTokenResult? = try {
            if (sessionId == null) {
                Timber.tag(TAG).d("PoToken SKIPPED: sessionId is null")
                null
            } else {
                poTokenGenerator.getWebClientPoToken(videoId, sessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "PoToken generation EXCEPTION")
            null
        }
        Timber.tag(TAG).d("PoToken: ${if (poTokenResult != null) "generated" else "unavailable"}")

        Timber.tag(TAG).d("Fetching main player response with ${MAIN_CLIENT.clientName}")
        val mainPlayerResponse =
            YouTube.player(
                videoId, playlistId, MAIN_CLIENT, signatureTimestamp,
                webPlayerPot = if (MAIN_CLIENT.useWebPoTokens) poTokenResult?.playerRequestPoToken else null
            ).getOrThrow()
        Timber.tag(TAG).d("Main response status: ${mainPlayerResponse.playabilityStatus.status}")

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var streamFromPoTokenClient = false

        // SABR data saved separately — tried first by MusicService, falls back to streamUrl
        var sabrStreamingUrl: String? = null
        var sabrStreamingPoToken: String? = null
        var sabrUstreamerConfig: String? = null
        var sabrFormat: PlayerResponse.StreamingData.Format? = null
        var sabrVideoDetails: PlayerResponse.VideoDetails? = null
        var sabrExpiresInSeconds: Int? = null

        for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamFromPoTokenClient = false

            val client: YouTubeClient
            if (clientIndex == -1) {
                client = MAIN_CLIENT
                streamPlayerResponse = mainPlayerResponse
                Timber.tag(TAG).d("--- Trying streams from main client: ${client.clientName} ---")
            } else {
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(TAG).d("--- Trying fallback ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName} ---")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    Timber.tag(TAG).d("Skipping ${client.clientName} - requires login")
                    continue
                }

                streamPlayerResponse =
                    YouTube.player(
                        videoId, playlistId, client, signatureTimestamp,
                        webPlayerPot = if (client.useWebPoTokens) poTokenResult?.playerRequestPoToken else null
                    ).getOrNull()
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(TAG).d("Status OK for ${client.clientName}")

                format = findFormat(streamPlayerResponse, audioQuality, connectivityManager)

                if (format == null) {
                    Timber.tag(TAG).d("No suitable format for ${client.clientName}")
                    continue
                }
                Timber.tag(TAG).d("Format: itag=${format.itag}, mime=${format.mimeType}, bitrate=${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId, streamPlayerResponse)
                if (streamUrl == null) {
                    Timber.tag(TAG).d("No stream URL for ${client.clientName}")
                    continue
                }
                streamFromPoTokenClient = client.useWebPoTokens

                // Append streaming poToken before validation
                if (streamFromPoTokenClient && poTokenResult?.streamingDataPoToken != null) {
                    val separator = if ("?" in streamUrl) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${poTokenResult.streamingDataPoToken}"
                    Timber.tag(TAG).d("Appended streaming PoToken to URL")
                }

                streamExpiresInSeconds =
                    streamPlayerResponse.streamingData?.expiresInSeconds
                        ?: deriveExpireSecondsFromUrl(streamUrl)
                        ?: defaultStreamTtlSeconds

                if (streamExpiresInSeconds <= 0) {
                    Timber.tag(TAG).d("Stream already expired, skipping")
                    continue
                }

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    Timber.tag(TAG).d("Last fallback -- skipping validation: ${client.clientName}")
                    break
                }

                val validationResult = validateStatus(streamUrl)
                if (validationResult) {
                    Timber.tag(TAG).d("Stream VALIDATED OK with ${client.clientName}")
                    break
                } else {
                    Timber.tag(TAG).d("Stream validation FAILED for ${client.clientName}")

                    // For web clients: try n-parameter transform and re-validate
                    if (client.useWebPoTokens) {
                        // Try CipherDeobfuscator n-transform first
                        var nTransformWorked = false
                        try {
                            val nTransformed = CipherDeobfuscator.transformNParamInUrl(streamUrl)
                            if (nTransformed != streamUrl) {
                                Timber.tag(TAG).d("CipherDeobfuscator n-transform applied, re-validating...")
                                if (validateStatus(nTransformed)) {
                                    Timber.tag(TAG).d("N-transformed URL VALIDATED OK!")
                                    streamUrl = nTransformed
                                    nTransformWorked = true
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "CipherDeobfuscator n-transform error")
                        }

                        // If CipherDeobfuscator n-transform failed, try EjsNTransformSolver on regular URL
                        if (!nTransformWorked) {
                            try {
                                val ejsTransformed = EjsNTransformSolver.transformNParamInUrl(streamUrl)
                                if (ejsTransformed != streamUrl) {
                                    Timber.tag(TAG).d("EJS n-transform applied, re-validating...")
                                    if (validateStatus(ejsTransformed)) {
                                        Timber.tag(TAG).d("EJS n-transformed URL VALIDATED OK!")
                                        streamUrl = ejsTransformed
                                        nTransformWorked = true
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "EJS n-transform error")
                            }
                        }

                        if (nTransformWorked) break

                        // N-transforms didn't fix the regular URL — save SABR data if available
                        if (sabrStreamingUrl == null && streamPlayerResponse?.streamingData?.serverAbrStreamingUrl != null) {
                            Timber.tag(TAG).d("SABR URL available -- saving for MusicService to try")
                            val rawSabrUrl = streamPlayerResponse.streamingData!!.serverAbrStreamingUrl!!
                            sabrStreamingUrl = try {
                                EjsNTransformSolver.transformNParamInUrl(rawSabrUrl)
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "SABR n-transform failed, using raw URL")
                                rawSabrUrl
                            }
                            sabrStreamingPoToken = poTokenResult?.streamingDataPoToken
                            sabrUstreamerConfig = streamPlayerResponse.playerConfig
                                ?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig
                            sabrFormat = format
                            sabrVideoDetails = videoDetails
                            sabrExpiresInSeconds = streamExpiresInSeconds
                        }

                        // Continue to fallback clients
                        Timber.tag(TAG).d("N-transforms failed for ${client.clientName}, trying next client...")
                    }
                }
            } else {
                Timber.tag(TAG).d("Status NOT OK for ${client.clientName}: ${streamPlayerResponse?.playabilityStatus?.status}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(TAG).e("All clients failed for $videoId")
            throw PlaybackException("All clients failed", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(TAG).e("Playability not OK: $errorReason")
            throw PlaybackException(errorReason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        if (format == null) {
            Timber.tag(TAG).e("No playable format for $videoId")
            throw PlaybackException("No playable format", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        if (streamUrl == null) {
            Timber.tag(TAG).e("No stream URL for $videoId")
            throw PlaybackException("No stream URL", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(TAG).e("Stream expired for $videoId")
            throw PlaybackException("Stream expired", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        val hasSabr = sabrStreamingUrl != null

        Timber.tag(TAG).d("=== Stream resolution SUCCESS: itag=${format.itag}, expires=${streamExpiresInSeconds}s, poToken=$streamFromPoTokenClient, sabr=$hasSabr ===")

        PlaybackData(
            audioConfig = audioConfig,
            videoDetails = videoDetails,
            playbackTracking = playbackTracking,
            format = format,
            streamUrl = streamUrl,
            streamExpiresInSeconds = streamExpiresInSeconds,
            isSabr = hasSabr,
            sabrStreamingUrl = sabrStreamingUrl,
            sabrItag = sabrFormat?.itag,
            sabrLmt = sabrFormat?.lastModified,
            streamingPoToken = sabrStreamingPoToken,
            ustreamerConfig = sabrUstreamerConfig,
        )
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(TAG).d("Fetching metadata for videoId: $videoId")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX)
            .onSuccess { Timber.tag(TAG).d("Metadata fetched") }
            .onFailure { Timber.tag(TAG).e(it, "Metadata fetch failed") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        return playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }
    }

    /**
     * Checks if the stream url returns a successful status.
     */
    private fun validateStatus(url: String): Boolean {
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
                .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
            val response = httpClient.newCall(requestBuilder.build()).execute()
            Timber.tag(TAG).d("Validation HTTP ${response.code}")
            return response.isSuccessful
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Validation exception")
            reportException(e)
        }
        return false
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? {
        return NewPipeExtractor.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(TAG).d("Signature timestamp: $it") }
            .onFailure {
                Timber.tag(TAG).e(it, "Signature timestamp failed")
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Resolves a playable stream URL from the format.
     * Tries custom cipher first, then NewPipe extractor, then StreamInfo fallback.
     */
    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
    ): String? {
        // 1. If format already has a direct URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(TAG).d("Using direct format URL")
            return format.url
        }

        // 2. For signatureCipher formats: try custom cipher first, then NewPipe
        if (format.signatureCipher != null) {
            val customUrl = try {
                CipherDeobfuscator.deobfuscateStreamUrl(format.signatureCipher!!, videoId)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Custom cipher deobfuscation failed")
                null
            }
            if (customUrl != null) {
                Timber.tag(TAG).d("Custom cipher deobfuscation succeeded")
                return customUrl
            }

            // Fallback to NewPipe extractor for cipher
            val extractorUrl = NewPipeExtractor.getStreamUrl(format, videoId)
            if (extractorUrl != null) {
                Timber.tag(TAG).d("NewPipe extractor deobfuscation succeeded")
                return extractorUrl
            }
        } else {
            // 3. Non-cipher format: try NewPipe extractor
            val extractorUrl = NewPipeExtractor.getStreamUrl(format, videoId)
            if (extractorUrl != null) {
                Timber.tag(TAG).d("NewPipe extractor URL obtained")
                return extractorUrl
            }
        }

        // 4. Last resort: StreamInfo fallback
        Timber.tag(TAG).d("Trying StreamInfo fallback")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(TAG).d("StreamInfo fallback succeeded (exact itag)")
                return streamUrl
            }

            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second
            if (audioStream != null) {
                Timber.tag(TAG).d("StreamInfo fallback succeeded (different itag)")
                return audioStream
            }
        }

        Timber.tag(TAG).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(TAG).d("Force refreshing for videoId: $videoId")
    }
}

private fun deriveExpireSecondsFromUrl(streamUrl: String): Int? {
    val uri = streamUrl.toUri()
    val expireEpoch = uri.getQueryParameter("expire")?.toLongOrNull()
        ?: uri.getQueryParameter("exp")?.toLongOrNull()
    return expireEpoch?.let { epoch ->
        val remainingMillis = epoch * 1000L - System.currentTimeMillis()
        if (remainingMillis > 0) (remainingMillis / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
    }
}
