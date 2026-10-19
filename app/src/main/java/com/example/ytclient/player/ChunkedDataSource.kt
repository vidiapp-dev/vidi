@file:androidx.media3.common.util.UnstableApi

package com.example.ytclient.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Transparent chunking wrapper around an HTTP [DataSource].
 *
 * googlevideo's `videoplayback` endpoint rejects large byte ranges with HTTP
 * 403 — empirically `bytes=0-1048575` (1 MB) succeeds but a full-file range
 * fails. It's designed for chunked delivery (that's what the `gir=yes` /
 * `clen=` params signal). ExoPlayer's progressive source, however, opens a
 * single open-ended request, which 403s.
 *
 * This wrapper bridges the gap: it accepts ExoPlayer's single open() spanning
 * the whole file, then internally issues sequential bounded range requests of
 * [chunkSize] bytes against [upstream], stitching them into one continuous
 * byte stream. ExoPlayer never sees the chunk boundaries.
 *
 * The overall length is taken from the DataSpec when known, else from the
 * `clen` (content-length) query parameter present in every googlevideo URL.
 */
class ChunkedDataSource(
    private val upstream: HttpDataSource,
    private val chunkSize: Long,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0       // overall remaining across all chunks
    private var chunkRemaining: Long = 0       // remaining in the current upstream chunk
    private var passThrough: Boolean = false   // no clen + unbounded → don't chunk
    private var opened: Boolean = false
    private var requestNumber: Int = 0         // &rn= sequence, like the real player

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position

        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            clen != null && clen > position -> clen - position
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true

        if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            // Can't determine the size → fall back to a single passthrough open.
            passThrough = true
            return upstream.open(dataSpec)
        }

        passThrough = false
        chunkRemaining = 0
        openNextChunk()
        return bytesRemaining
    }

    private fun openNextChunk() {
        val want = minOf(chunkSize, bytesRemaining)
        val start = position
        val end = position + want - 1

        // Request the byte range the way the official YouTube player does: as a
        // `&range=START-END` QUERY parameter (+ an incrementing `&rn=`), with NO
        // HTTP Range header — googlevideo answers 200 with exactly those bytes.
        // googlevideo tolerates a header-based progressive read from offset 0, but
        // 403s a header-based Range that JUMPS mid-file (random access / seek);
        // the query-param form is accepted at any offset. (DataSpec position 0 +
        // unset length ⇒ OkHttpDataSource sends no Range header.)
        val chunkSpec = DataSpec.Builder()
            .setUri(rangedUri(start, end))
            .setHttpRequestHeaders(baseSpec!!.httpRequestHeaders)
            .build()

        var attempt = 0
        while (true) {
            try {
                upstream.open(chunkSpec)
                chunkRemaining = want
                return
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                Log.w(
                    "ChunkedDataSource",
                    "range=$start-$end (${want}B) → HTTP ${e.responseCode}" +
                        (if (attempt < MAX_CHUNK_RETRIES && e.responseCode == 403) " — retrying" else ""),
                )
                if (e.responseCode == 403 && attempt < MAX_CHUNK_RETRIES) {
                    attempt++
                    try { upstream.close() } catch (_: Throwable) {}
                    try { Thread.sleep(250L * attempt) } catch (_: InterruptedException) {}
                    continue
                }
                throw e
            }
        }
    }

    /** Original URL + `&range=START-END&rn=N` (a fresh range each call — baseSpec.uri
     *  never carries a range itself, so this doesn't accumulate). */
    private fun rangedUri(start: Long, end: Long): Uri =
        baseSpec!!.uri.buildUpon()
            .appendQueryParameter("range", "$start-$end")
            .appendQueryParameter("rn", "${requestNumber++}")
            .appendQueryParameter("rbuf", "0")
            .build()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (passThrough) return upstream.read(buffer, offset, length)

        while (true) {
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            if (chunkRemaining == 0L) {
                upstream.close()
                openNextChunk()
            }
            val toRead = minOf(length.toLong(), chunkRemaining).toInt()
            val read = upstream.read(buffer, offset, toRead)
            if (read == C.RESULT_END_OF_INPUT) {
                // Upstream returned fewer bytes than the requested range — treat
                // as end-of-stream to avoid spinning. (Shouldn't normally happen
                // mid-file; chunk exhaustion is detected via chunkRemaining==0.)
                if (chunkRemaining > 0L) return C.RESULT_END_OF_INPUT
                chunkRemaining = 0
                continue
            }
            position += read
            chunkRemaining -= read
            bytesRemaining -= read
            return read
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstreamFactory.createDataSource(), chunkSize)
    }

    companion object {
        // 1 MB is empirically accepted by googlevideo; full-file ranges 403.
        const val DEFAULT_CHUNK_SIZE: Long = 1L * 1024 * 1024

        // In-place retries for a transiently-throttled (403) chunk before
        // surfacing the error to ExoPlayer's load-error policy.
        const val MAX_CHUNK_RETRIES: Int = 3
    }
}
