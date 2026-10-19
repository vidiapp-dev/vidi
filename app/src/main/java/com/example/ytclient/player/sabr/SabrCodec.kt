package com.example.ytclient.player.sabr

import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream

/**
 * Builds the protobuf body for a `VideoPlaybackAbrRequest` (the POST body sent
 * to `serverAbrStreamingUrl`). Mirrors what LuanRT/googlevideo's SABR client
 * sends: selected/preferred formats, what we've buffered, player time, the
 * opaque ustreamer config, and a streamer context carrying the client info,
 * PO token and (on follow-up requests) the playback cookie from the previous
 * NEXT_REQUEST_POLICY.
 */
object SabrRequestBuilder {

    fun build(
        config: SabrConfig,
        selected: List<SabrFormat>,
        playerTimeMs: Long,
        buffered: List<SabrBufferedRange>,
        playbackCookie: ByteArray?,
    ): ByteArray {
        val clientInfo = PbClientInfo.newBuilder()
            .setClientName(config.clientInfo.clientName)
            .setClientVersion(config.clientInfo.clientVersion)
            .setOsName(config.clientInfo.osName)
            .setOsVersion(config.clientInfo.osVersion)
            .also { b -> config.clientInfo.deviceMake?.let { b.setDeviceMake(it) } }
            .also { b -> config.clientInfo.deviceModel?.let { b.setDeviceModel(it) } }
            .build()

        val streamerContext = PbStreamerContext.newBuilder()
            .setClientInfo(clientInfo)
            .also { b -> config.poToken?.let { b.setPoToken(ByteString.copyFrom(it)) } }
            .also { b -> playbackCookie?.let { b.setPlaybackCookie(ByteString.copyFrom(it)) } }
            .build()

        val abrState = PbClientAbrState.newBuilder()
            .setPlayerTimeMs(playerTimeMs)
            .setEnabledTrackTypesBitfield(0) // 0 = audio + video
            .build()

        val req = PbVideoPlaybackAbrRequest.newBuilder()
            .setClientAbrState(abrState)
            .addAllSelectedFormatIds(selected.map { formatId(it) })
            .addAllBufferedRanges(buffered.map { bufferedRange(it) })
            .setVideoPlaybackUstreamerConfig(ByteString.copyFrom(config.ustreamerConfig))
            .addAllPreferredVideoFormatIds(selected.filter { it.isVideo }.map { formatId(it) })
            .addAllPreferredAudioFormatIds(selected.filter { !it.isVideo }.map { formatId(it) })
            .setStreamerContext(streamerContext)
            .build()

        return req.toByteArray()
    }

    private fun formatId(f: SabrFormat): PbFormatId =
        PbFormatId.newBuilder()
            .setItag(f.itag)
            .setLastModified(f.lastModified)
            .also { b -> f.xtags?.let { b.setXtags(it) } }
            .build()

    private fun bufferedRange(r: SabrBufferedRange): PbBufferedRange =
        PbBufferedRange.newBuilder()
            .setFormatId(formatId(r.format))
            .setStartTimeMs(r.startTimeMs)
            .setDurationMs(r.durationMs)
            .setStartSegmentIndex(r.startSegmentIndex)
            .setEndSegmentIndex(r.endSegmentIndex)
            .build()
}

/**
 * Decodes one SABR response body (UMP-framed) into a [SabrResponse]:
 * pairs MEDIA_HEADER → MEDIA* → MEDIA_END into assembled [SabrSegment]s and
 * collects the control signals (init metadata, redirect, playback cookie,
 * protection status, errors).
 */
object SabrResponseDecoder {

    fun decode(body: ByteArray): SabrResponse {
        val headers = HashMap<Int, PbMediaHeader>()          // header_id → header
        val buffers = HashMap<Int, ByteArrayOutputStream>()  // header_id → media bytes
        val segments = ArrayList<SabrSegment>()
        val inits = ArrayList<SabrInitMetadata>()
        var redirect: String? = null
        var cookie: ByteArray? = null
        var backoff = 0
        var protection: Int? = null
        var error: String? = null

        fun finalize(headerId: Int) {
            val h = headers.remove(headerId) ?: return
            val data = buffers.remove(headerId)?.toByteArray() ?: ByteArray(0)
            segments.add(
                SabrSegment(
                    itag = h.itag,
                    lastModified = h.lmt,
                    isInit = h.isInitSeg,
                    sequenceNumber = h.sequenceNumber,
                    startMs = h.startMs,
                    durationMs = h.durationMs,
                    data = data,
                ),
            )
        }

        for (part in UmpReader.parse(body)) {
            when (part.type) {
                UmpPartType.MEDIA_HEADER -> {
                    val mh = PbMediaHeader.parseFrom(part.data)
                    headers[mh.headerId] = mh
                    buffers[mh.headerId] = ByteArrayOutputStream()
                }

                UmpPartType.MEDIA -> {
                    val r = UmpReader.readVarint(part.data, 0) ?: continue
                    val headerId = r.first.toInt()
                    val off = r.second
                    buffers.getOrPut(headerId) { ByteArrayOutputStream() }
                        .write(part.data, off, part.data.size - off)
                }

                UmpPartType.MEDIA_END -> {
                    val r = UmpReader.readVarint(part.data, 0) ?: continue
                    finalize(r.first.toInt())
                }

                UmpPartType.FORMAT_INITIALIZATION_METADATA -> {
                    val m = PbFormatInitMetadata.parseFrom(part.data)
                    val durMs = if (m.durationTimescale > 0) {
                        m.durationUnits * 1000L / m.durationTimescale
                    } else {
                        0L
                    }
                    inits.add(
                        SabrInitMetadata(
                            itag = m.formatId.itag,
                            lastModified = m.formatId.lastModified,
                            mimeType = if (m.hasMimeType()) m.mimeType else null,
                            totalSegments = m.endSegmentNumber,
                            durationMs = durMs,
                        ),
                    )
                }

                UmpPartType.NEXT_REQUEST_POLICY -> {
                    val p = PbNextRequestPolicy.parseFrom(part.data)
                    backoff = p.backoffTimeMs
                    if (p.hasPlaybackCookie()) cookie = p.playbackCookie.toByteArray()
                }

                UmpPartType.SABR_REDIRECT ->
                    redirect = PbSabrRedirect.parseFrom(part.data).url

                UmpPartType.SABR_ERROR ->
                    error = PbSabrError.parseFrom(part.data).type

                UmpPartType.STREAM_PROTECTION_STATUS ->
                    protection = PbStreamProtectionStatus.parseFrom(part.data).status
            }
        }

        // The server often omits MEDIA_END for the final segment of a response.
        for (id in headers.keys.toList()) finalize(id)

        return SabrResponse(
            segments = segments,
            initMetadata = inits,
            redirectUrl = redirect,
            playbackCookie = cookie,
            backoffTimeMs = backoff,
            protectionStatus = protection,
            sabrError = error,
        )
    }
}
