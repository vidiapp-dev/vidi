package com.example.ytclient.sabr;

import android.net.Uri;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor;
import androidx.media3.exoplayer.source.chunk.ChunkHolder;
import androidx.media3.exoplayer.source.chunk.ChunkSource;
import androidx.media3.exoplayer.source.chunk.ContainerMediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.io.IOException;
import java.util.List;

/**
 * Tier-2: feeds the media3 chunk framework one SABR segment per chunk. Because the framework drives
 * loading by chunk INDEX (mapped from time), seeking is time-based and real, unlike the v1 byte
 * stream that could not land a seek. One {@link FragmentedMp4Extractor} is shared per track via a
 * {@link BundledChunkExtractor}; the init segment is prepended onto each media segment so each chunk
 * is a complete init + one fragment.
 *
 * <p>Ported from PipePipeClient PR #47.</p>
 */
@UnstableApi
final class SabrChunkSource implements ChunkSource {

    private final SabrSessionHolder holder;
    private final YoutubeSabrFormat format;
    private final Format trackFormat;
    private final int trackType;
    private final Localization localization;

    @Nullable
    private IOException fatalError;

    SabrChunkSource(final SabrSessionHolder holder,
                    final YoutubeSabrFormat format,
                    final Format trackFormat,
                    final int trackType,
                    final Localization localization) {
        this.holder = holder;
        this.format = format;
        this.trackFormat = trackFormat;
        this.trackType = trackType;
        this.localization = localization;
    }

    @Override
    public long getAdjustedSeekPositionUs(final long positionUs, final SeekParameters seekParameters) {
        // Resolve the seek the same way media3's own DASH chunk source does: offer the two
        // surrounding sync points (the start of the segment containing positionUs and the start of
        // the next segment) and let the caller's SeekParameters pick. With the default (EXACT)
        // parameters this returns positionUs itself — the chunk framework then reloads the
        // containing segment and decode-discards up to the exact target, so the seek LANDS at the
        // requested position.
        //
        // This used to unconditionally snap to the START of the containing segment, i.e. every seek
        // resolved up to a whole segment (~5s) BEFORE the requested target. That was the root cause
        // of the SponsorBlock skip loop: a skip to a segment run's end that fell mid-chunk landed
        // back INSIDE the sponsor segment, the poll re-detected it and re-issued the same skip,
        // forever (mitigated only by escalation margins that had to blindly out-grow the chunk size).
        final YoutubeSabrStreamState state = holder.session.getStreamState();
        final int seq = state.getSegmentNumberAtOrAfterTimeMs(format, positionUs / 1000);
        final long firstSyncUs = Math.max(0, state.getSegmentStartMs(format, seq)) * 1000;
        long secondSyncUs = firstSyncUs;
        final long endSeq = state.getEndSegment(format);
        if (firstSyncUs < positionUs && (endSeq <= 0 || seq < endSeq)) {
            final long nextStartMs = state.getSegmentStartMs(format, seq + 1);
            if (nextStartMs * 1000 > firstSyncUs) {
                secondSyncUs = nextStartMs * 1000;
            }
        }
        return seekParameters.resolveSeekPositionUs(positionUs, firstSyncUs, secondSyncUs);
    }

    @Override
    public void maybeThrowError() throws IOException {
        if (fatalError != null) {
            throw fatalError;
        }
    }

    @Override
    public int getPreferredQueueSize(final long playbackPositionUs,
                                     final List<? extends MediaChunk> queue) {
        return queue.size();
    }

    @Override
    public boolean shouldCancelLoad(final long playbackPositionUs, final Chunk loadingChunk,
                                    final List<? extends MediaChunk> queue) {
        return false;
    }

    @Override
    public void getNextChunk(final LoadingInfo loadingInfo, final long loadPositionUs,
                             final List<? extends MediaChunk> queue, final ChunkHolder out) {
        final int nextSeq;
        if (queue.isEmpty()) {
            nextSeq = holder.session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(format, loadPositionUs / 1000);
        } else {
            nextSeq = (int) (queue.get(queue.size() - 1).getNextChunkIndex());
        }
        final long endSeq = holder.session.getStreamState().getEndSegment(format);
        if (endSeq > 0 && nextSeq > endSeq) {
            out.endOfStream = true;
            return;
        }
        out.chunk = newMediaChunk(nextSeq);
    }

    private Chunk newMediaChunk(final int seq) {
        final long startMs = holder.session.getStreamState().getSegmentStartMs(format, seq);
        final long endMs = holder.session.getStreamState().getSegmentEndMs(format, seq);
        final long startUs = Math.max(0, startMs) * 1000;
        final long endUs = (endMs > 0 ? endMs : startMs) * 1000;
        // Each chunk is exactly one fragment carrying absolute timestamps, so clipping is only ever a
        // no-op (bounds correct) or HARMFUL: the first chunk is built before its init segment's sidx
        // is parsed, so the bounds above are a uniform averageDurationMs guess (e.g. [0,5000] for a
        // real ~10s first segment) which clips off the segment's tail → an audible AAC discontinuity
        // / garbled start. Only clip once the precise index is loaded; otherwise let the extractor's
        // real sample timestamps define the chunk.
        final boolean clip = holder.session.getStreamState().hasSegmentIndex(format);
        final long clippedStartUs = clip ? startUs : C.TIME_UNSET;
        final long clippedEndUs = clip ? endUs : C.TIME_UNSET;
        final DataSpec spec = new DataSpec(Uri.parse("sabrseg://" + format.getItag() + "/" + seq));
        // Fresh extractor per chunk: the data source prepends the init, so each chunk is a complete
        // init + one fragment. Absolute fragment timestamps -> sampleOffsetUs = 0. Pick the container
        // by mime: YouTube ships VP9/Opus in WebM (Matroska) and AVC/AAC in fragmented mp4.
        final String mime = format.getMimeType();
        final Extractor extractorImpl = mime != null && mime.contains("webm")
                ? new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
                : new FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED);
        final ChunkExtractor extractor = new BundledChunkExtractor(
                extractorImpl, trackType, trackFormat);
        return new ContainerMediaChunk(
                new SabrSegmentDataSource(holder, format, localization, /* prependInit= */ true),
                spec, trackFormat, C.SELECTION_REASON_UNKNOWN, null,
                startUs, endUs, /* clippedStartTimeUs= */ clippedStartUs,
                /* clippedEndTimeUs= */ clippedEndUs,
                /* chunkIndex= */ seq, /* chunkCount= */ 1, /* sampleOffsetUs= */ 0L,
                extractor);
    }

    @Override
    public void onChunkLoadCompleted(final Chunk chunk) {
    }

    @Override
    public boolean onChunkLoadError(final Chunk chunk, final boolean cancelable,
                                    final LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo,
                                    final LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        // Let the framework apply its retry/backoff policy.
        return false;
    }

    @Override
    public void release() {
    }
}
