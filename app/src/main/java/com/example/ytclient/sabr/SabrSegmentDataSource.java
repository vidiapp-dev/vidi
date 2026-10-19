package com.example.ytclient.sabr;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;

import java.io.IOException;

/**
 * Tier-2 chunk source helper: a {@link DataSource} that serves exactly ONE SABR segment (the init
 * segment or one media segment) from the session cache, then ends. The chunk framework
 * ({@code ChunkSampleStream}) opens one of these per chunk, so seeking is handled by the framework
 * picking the chunk index, NOT by byte-skipping a continuous stream (which the v1 source could not
 * land).
 *
 * <p>The segment is identified by the {@link DataSpec} uri: {@code sabrseg://<itag>/init} or
 * {@code sabrseg://<itag>/<sequenceNumber>}. Ported from PipePipeClient PR #47.</p>
 */
@UnstableApi
public final class SabrSegmentDataSource implements DataSource {

    private static final String TAG = "SabrSegmentSource";
    // Gated tracing for the seek-trigger heuristics (rewind / forward-jump). Off in production; flip
    // to true to see why a read triggered a reposition. Stalls are always logged.
    private static final boolean DEBUG = false;
    private static final long WAIT_MS = 250;
    private static final long STALL_MS = 120_000;
    // After waiting this long for a media segment that's BEHIND the buffered edge, treat it as a
    // backward seek onto an evicted segment and ask the pump to reposition the session there.
    private static final long REFETCH_AFTER_MS = 2_000;
    // The pump only fills ~one read-ahead cushion past the furthest-READ track, then throttles. A
    // still-uncached target further than this past the reader head is unreachable by sequential fill
    // (during a forward seek the reader head is frozen at the pre-seek position because no new
    // segment has been served yet, so the pump sits throttled while we block) → jump the session to
    // it. Compared against the reader head (not the buffered edge) so a normal rebuffer — where the
    // reader head has caught up to the edge — still fills the next segment instead of needlessly
    // jumping. Kept in step with the pump's READAHEAD_CUSHION_MS.
    private static final long FORWARD_JUMP_MARGIN_MS = 10_000;

    private final SabrSessionHolder holder;
    private final YoutubeSabrFormat format;
    private final Localization localization;
    // Prepend the init segment so each media chunk is a self-contained fmp4 (init + one fragment),
    // which a fresh FragmentedMp4Extractor parses fully. SABR's init isn't a clean standalone atom
    // boundary, so feeding it on its own (DASH-style InitializationChunk) hit an EOF mid-atom.
    private final boolean prependInit;

    @Nullable
    private Uri uri;
    // The two source arrays are served back-to-back (init then media) WITHOUT concatenating into a
    // third array — that per-chunk copy of every media segment was a big recurring large-object
    // allocation → heavy GC → audio micro-stutter. Both reference the cached segment bytes directly.
    @Nullable
    private byte[] initData;
    @Nullable
    private byte[] mediaData;
    private int totalLen;
    private int pos;
    private boolean opened;
    private volatile boolean canceled;

    public SabrSegmentDataSource(final SabrSessionHolder holder,
                                 final YoutubeSabrFormat format,
                                 final Localization localization,
                                 final boolean prependInit) {
        this.holder = holder;
        this.format = format;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    @Override
    public void addTransferListener(final TransferListener transferListener) {
        // Bandwidth metering not wired for the SABR source.
    }

    @Override
    public long open(final DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        this.canceled = false;
        this.pos = (int) Math.max(0, dataSpec.position);
        final SabrSegmentRequest request = requestFromUri(dataSpec.uri);
        if (prependInit && !request.isInitializationSegment()) {
            this.initData = awaitSegment(SabrSegmentRequest.initialization(format));
            this.mediaData = awaitSegment(request);
        } else {
            this.initData = null;
            this.mediaData = awaitSegment(request);
        }
        this.opened = true;
        this.totalLen = (initData != null ? initData.length : 0) + mediaData.length;
        final int remaining = totalLen - pos;
        return dataSpec.length == C.LENGTH_UNSET ? remaining : Math.min(dataSpec.length, remaining);
    }

    @Override
    public int read(final byte[] target, final int offset, final int length) {
        if (length == 0) {
            return 0;
        }
        if (mediaData == null || pos >= totalLen) {
            return C.RESULT_END_OF_INPUT;
        }
        // Serve init bytes first, then media bytes — straight from the cached arrays, no concat. One
        // call never crosses the init/media boundary (returning short is allowed); the extractor calls
        // again for the rest.
        final int initLen = initData != null ? initData.length : 0;
        final int toCopy;
        if (pos < initLen) {
            toCopy = Math.min(length, initLen - pos);
            System.arraycopy(initData, pos, target, offset, toCopy);
        } else {
            final int mediaPos = pos - initLen;
            toCopy = Math.min(length, mediaData.length - mediaPos);
            System.arraycopy(mediaData, mediaPos, target, offset, toCopy);
        }
        pos += toCopy;
        return toCopy;
    }

    private SabrSegmentRequest requestFromUri(final Uri u) throws IOException {
        // sabrseg://<itag>/<init|seq>
        final String seg = u.getLastPathSegment();
        if (seg == null) {
            throw new IOException("Bad SABR segment uri: " + u);
        }
        if ("init".equals(seg)) {
            return SabrSegmentRequest.initialization(format);
        }
        try {
            return SabrSegmentRequest.media(format, Integer.parseInt(seg));
        } catch (final NumberFormatException e) {
            throw new IOException("Bad SABR segment uri: " + u, e);
        }
    }

    /** Block until the pump has cached this segment, or give up on a real stall / cancellation. */
    private byte[] awaitSegment(final SabrSegmentRequest request) throws IOException {
        final SabrStreamPump pump = holder.getPump(localization);
        final long waitStart = System.currentTimeMillis();
        long lastRefetchMs = 0;
        while (true) {
            if (canceled) {
                throw new IOException("SABR segment read canceled");
            }
            pump.ensureStarted();
            final SabrMediaSegment segment = pump.getCached(request);
            if (segment != null) {
                if (!segment.getHeader().isInitSegment()) {
                    // Tell the pump how far this track has been loaded so it keeps feeding ahead
                    // (and repositions after a seek). Without this readerHead stayed 0 and the pump
                    // throttled forever after the initial fill.
                    holder.setReaderPositionMs(format.getItag(),
                            segment.getHeader().getStartMs() + segment.getHeader().getDurationMs());
                }
                return segment.getData();
            }
            if (pump.isFatal()) {
                throw new IOException("SABR pump fatal for itag=" + format.getItag());
            }
            // We've waited a beat and the segment still isn't cached. Two seek cases the forward pump
            // can't serve on its own, each repositioning the session so the server resends from the
            // target (and dropping our read position onto it so eviction + pacing follow the seek):
            //   - behind the buffered edge  -> backward seek onto an evicted segment (rewind);
            //   - far ahead of the reader   -> distant forward seek beyond sequential-fill reach (jump).
            // A merely-slow next segment (just past the reader head, normal rebuffer) matches neither
            // and is left to the pump, so ordinary forward playback is untouched.
            if (!request.isInitializationSegment()) {
                final long now = System.currentTimeMillis();
                if (now - waitStart > REFETCH_AFTER_MS && now - lastRefetchMs > REFETCH_AFTER_MS) {
                    final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
                    final long myReaderMs = holder.getReaderPositionMs(format.getItag());
                    final long segStartMs = holder.session.getStreamState()
                            .getSegmentStartMs(format, request.getSequenceNumber());
                    // Rewind ONLY for a genuine backward move of THIS track (re-reading a segment behind
                    // where it already read = a backward seek onto evicted data). Comparing against the
                    // global min-buffered EDGE instead falsely rewound a track's normal NEXT segment
                    // whenever the OTHER track was buffered further ahead (audio ~10s vs video ~5s
                    // segments → edge sits past video's next seq) — yanking the pump backward for a
                    // segment that was simply still downloading, starving audio.
                    if (myReaderMs >= 0 && segStartMs < myReaderMs) {
                        if (DEBUG) {
                            Log.i(TAG, "itag=" + format.getItag()
                                    + " seq=" + request.getSequenceNumber()
                                    + " segStart=" + segStartMs + "ms behind myReader=" + myReaderMs
                                    + "ms -> rewind (edge=" + edgeMs
                                    + " tail=" + holder.getReaderTailMs()
                                    + " head=" + holder.getReaderHeadMs()
                                    + " cachedBytes=" + holder.session.getCachedBytes() + ")");
                        }
                        holder.setReaderPositionMs(format.getItag(), segStartMs);
                        pump.requestRefetchFrom(request);
                        lastRefetchMs = now;
                    } else if (segStartMs > holder.getReaderHeadMs() + FORWARD_JUMP_MARGIN_MS) {
                        // Distant forward seek: this segment is further past the (frozen) reader head
                        // than the throttled forward pump can sequentially reach, so it would just
                        // drip the gap and stall. Drop our read position onto the target so eviction +
                        // pacing follow the jump, and ask the pump to reposition the session there so
                        // the server streams fresh from the target.
                        if (DEBUG) {
                            Log.i(TAG, "itag=" + format.getItag()
                                    + " seq=" + request.getSequenceNumber()
                                    + " segStart=" + segStartMs + "ms ahead of readerHead="
                                    + holder.getReaderHeadMs() + "ms -> forward-jump"
                                    + " (myReader=" + holder.getReaderPositionMs(format.getItag())
                                    + " tail=" + holder.getReaderTailMs()
                                    + " cachedBytes=" + holder.session.getCachedBytes() + ")");
                        }
                        holder.setReaderPositionMs(format.getItag(), segStartMs);
                        pump.requestForwardJumpTo(request);
                        lastRefetchMs = now;
                    }
                }
            }
            // Stall = THIS segment hasn't arrived within STALL_MS of us actually waiting for it. Do
            // NOT use the pump's "time since it last produced a segment": the pump legitimately stops
            // producing while throttled (buffer full, edge far ahead), so that clock goes stale and
            // the first cache miss after a long throttle false-stalls at ~STALL_MS. That was the
            // recurring ~2min freeze on longer/higher-bitrate streams.
            if (System.currentTimeMillis() - waitStart > STALL_MS) {
                Log.w(TAG, "stalled itag=" + format.getItag() + " seq="
                        + (request.isInitializationSegment() ? "init" : request.getSequenceNumber())
                        + " after " + STALL_MS + "ms (edge="
                        + holder.session.getStreamState().getMinBufferedEndMs()
                        + "ms readerHead=" + holder.getReaderHeadMs() + "ms)");
                throw new IOException("SABR segment stalled for itag=" + format.getItag());
            }
            try {
                Thread.sleep(WAIT_MS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted awaiting SABR segment", ie);
            }
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        canceled = true;
        initData = null;
        mediaData = null;
        opened = false;
    }
}
