package com.example.ytclient.sabr;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.util.List;

/**
 * Single consumer of a {@link YoutubeSabrSession}: one daemon thread pumps the server-driven SABR
 * stream and fills the session's (concurrent) segment cache ahead of the play head. The server
 * paces us with policy-only responses once we are far enough ahead. Both the audio and video
 * {@link SabrSegmentDataSource}s only read the cache, so they never fight over the session or block each
 * other on a network round-trip, which is exactly what starved a track in the old on-demand approach.
 *
 * <p>Ported from PipePipeClient PR #47.</p>
 */
final class SabrStreamPump {

    private static final String TAG = "SabrStreamPump";
    // Gated seek-event tracing (cold-prime / rewind / forward-jump). Off in production; flip to true
    // to follow how the pump reacts to seeks. Fatal errors are always logged.
    private static final boolean DEBUG = true;
    private static final long IDLE_POLL_MS = 400;     // server paced us / nothing new this round
    private static final long ERROR_RETRY_MS = 1000;  // transient network error
    // Raw IOExceptions (socket resets, TLS failures, a 401/403 that isn't decoded into a SABR
    // "protected" response by the layer above) used to retry forever at ERROR_RETRY_MS with no
    // escalation — if the underlying cause is a genuinely dead session (e.g. an outer auth/session
    // token expired in a way the SABR protocol layer's own protected-response handling doesn't
    // catch), playback would just spin retrying the same failing request indefinitely instead of
    // ever reaching the fatal/break path that lets the ViewModel rebuild with a fresh session/token.
    // Bounding the streak by wall-clock time (not iteration count, since ERROR_RETRY_MS itself never
    // changes) gives real transient blips a fair chance while still guaranteeing forward progress.
    private static final long MAX_IO_ERROR_STREAK_MS = 20_000;
    // no reads for this long -> playback is gone. MUST stay above READAHEAD_CUSHION_MS: once the
    // player buffer is full it stops reading us for ~cushion seconds, and killing the pump in that
    // window left the cache to drain dry -> periodic rebuffering.
    private static final long IDLE_STOP_MS = 90_000;

    // Margin the buffered edge stays ahead of the furthest-read track, i.e. how deep we pre-fill.
    // A deeper cushion absorbs GC pauses / network jitter / decode hiccups so playback doesn't keep
    // hitting the end of a shallow buffer (the small-buffering-pauses symptom). It was once kept at
    // 10s under the theory that a deeper pre-fill races the reported playerTime to ~60s at startup and
    // trips YouTube's status=3 wall — but that wall was actually the out-of-page attestation (fixed by
    // the in-page att/get GVS pot), not the buffer depth, so this is safe to grow. Still bounded by
    // MAX_AHEAD_BYTES so a high-bitrate stream can't OOM.
    private static final long READAHEAD_CUSHION_MS = 30_000;
    // Hard byte ceiling on read-ahead so a high-bitrate (4K) stream can't OOM the heap: 50s of 4K is
    // ~160MB and crashed. ~100MB still covers the player's ~30s read-ahead, well under the OOM line.
    private static final long MAX_AHEAD_BYTES = 100L * 1024 * 1024;
    // Keep this much already-played video in the cache so a short backward seek lands on cached
    // segments instead of a hole (eviction used to drop everything the reader passed, so any rewind
    // hit an evicted segment the pump never re-fetches -> dead buffer). Bounded, same order as the
    // forward cushion. Rewinds beyond this still need a session re-request (separate follow-up).
    private static final long BACK_BUFFER_MS = 30_000;
    // Fallback back-buffer used when the cache is already over the byte budget: at high bitrate (4K)
    // a 30s back-buffer + readahead exceeds MAX_AHEAD_BYTES, and since eviction can't drop segments
    // within the back-buffer window the cache can't drain -> the pump throttles forever and stalls.
    // Shrinking the back-buffer when over budget lets eviction free bytes so playback keeps fetching.
    private static final long MIN_BACK_BUFFER_MS = 5_000;
    /** {@code StreamProtectionStatus.status} 2 — the server's "grace" answer. */
    private static final int PROTECTION_GRACE = 2;
    /** {@code StreamProtectionStatus.status} 3 — media refused pending (re-)attestation. */
    private static final int PROTECTION_PROTECTED = 3;

    private final YoutubeSabrSession session;
    private final SabrSessionHolder holder;
    private final Localization localization;

    private volatile boolean started;
    private volatile boolean stopped;

    /**
     * TERMINAL shutdown flag — distinct from {@link #stopped}, which is only "idle for now" and is
     * deliberately revivable by {@link #ensureStarted()} (a read means playback is live again).
     *
     * Without this distinction a released session RESURRECTS ITSELF: the holder is released on a
     * video change / quality switch / retry, but the departing player's data sources can still
     * issue reads afterwards (ExoPlayer.release() is asynchronous, and was observed timing out
     * outright), and each such read called ensureStarted(), cleared `stopped`, and spawned a brand
     * new pump thread. Field logs showed sessions from PREVIOUS videos still POSTing to GVS every
     * ~2s indefinitely — plausibly the trigger for the intermittent protection status=3 on the
     * CURRENT video, since several concurrent SABR sessions per client is exactly what abuse
     * detection looks for. Once released, a pump must stay dead.
     */
    private volatile boolean released;
    private volatile boolean fatal;
    private volatile long lastReadMs;
    // One-shot: has the cold-start prime (jump to holder.startPositionMs) run yet?
    private boolean primed;
    // Set by a reader blocked on an evicted segment behind the edge (backward seek); the loop
    // repositions the session onto it next round. Single-slot: the latest rewind target wins.
    private volatile SabrSegmentRequest pendingRefetch;
    // Guards against servicing the same rewind target forever when re-fetching it can never actually
    // satisfy the blocked reader (e.g. the cached copy is corrupt/fails downstream extraction, so a
    // same-key re-fetch is a cache no-op without the discardCachedSegment() call below). Reset
    // whenever the target changes.
    private int consecutiveRewindRepeats;
    private SabrSegmentRequest lastRewindTarget;
    private static final int MAX_CONSECUTIVE_REWIND_REPEATS = 5;
    // Set by a reader blocked on a segment far AHEAD of the edge (distant forward seek); the loop
    // jumps the session's play head onto it so the server streams fresh from there instead of the
    // pump dripping every intervening segment (which throttles and stalls before reaching a far
    // target). Single-slot: the latest forward-seek target wins.
    private volatile SabrSegmentRequest pendingForwardJump;
    private Thread thread;

    SabrStreamPump(@NonNull final YoutubeSabrSession session,
                   @NonNull final SabrSessionHolder holder,
                   @NonNull final Localization localization) {
        this.session = session;
        this.holder = holder;
        this.localization = localization;
    }

    /** Start (or restart, if it idled out) the pump thread, and mark the session as actively read. */
    void ensureStarted() {
        lastReadMs = System.currentTimeMillis();
        // A released pump never restarts, however late a straggling read arrives — see `released`.
        if (released || fatal || (started && !stopped)) {
            return;
        }
        synchronized (this) {
            if (released || fatal || (started && !stopped)) {
                return;
            }
            // CRITICAL: `stopped` only means "the loop has been ASKED to stop", not "the loop has
            // exited". The pump thread spends most of its time inside session.pumpOnce(), which is a
            // blocking HTTP round trip that does NOT abort on Thread.interrupt(); after stop() it can
            // keep running for seconds. Starting a second thread here would put TWO pump threads on
            // ONE YoutubeSabrSession, which breaks the protocol two ways:
            //   * YoutubeSabrSession documents its stream state as "mutated only by the single pump
            //     thread in pumpOnce" — concurrent mutation corrupts per-format segment bookkeeping
            //     (observed as one track, usually audio, silently stopping being fed), and
            //   * two overlapping SABR requests reuse/clobber the same playback cookie and streamer
            //     context, which the server answers with StreamProtectionStatus=3 once the ~60s grace
            //     burst ends and it starts enforcing — the exact prot=3-at-60s failure.
            // So: never overlap. Readers poll getCached() on a WAIT_MS timer, so simply declining to
            // start now self-heals — the next poll (a few ms later) starts the pump once the previous
            // thread has actually exited and cleared `thread` in loop()'s finally block.
            final Thread previous = thread;
            if (previous != null && previous.isAlive()) {
                Log.i(TAG, "SABR pump: restart deferred, previous pump thread still winding down"
                        + " (session=" + Integer.toHexString(System.identityHashCode(session)) + ")");
                return;
            }
            stopped = false;
            started = true;
            thread = new Thread(this::loop, "SabrStreamPump");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * PERMANENT shutdown: stop the pump and make sure no later read can revive it. This is what
     * playback teardown (holder release) must call; {@link #stop()} alone only idles it.
     */
    void release() {
        released = true;
        stop();
    }

    /** Stop the pump thread (idle — a later read may legitimately revive it via ensureStarted). */
    void stop() {
        synchronized (this) {
            stopped = true;
            // Don't self-interrupt: stop() is also reached from the pump thread itself via
            // evict-on-fatal, and setting our own interrupt flag could break a later blocking call.
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    @Nullable
    SabrMediaSegment getCached(@NonNull final SabrSegmentRequest request) {
        // revive the pump if it idled out: any read means playback is live again.
        ensureStarted();
        return session.getCachedSegment(request);
    }

    boolean isFatal() {
        return fatal;
    }

    /**
     * LOCAL ADDITION. Set {@link #fatal} and notify the holder immediately, from whichever of the
     * four give-up sites in {@link #loop()} reaches it. Previously each site set {@code fatal = true}
     * directly with no signal beyond the flag itself; the view-model only ever learned about it
     * indirectly, by ExoPlayer eventually reading an absent segment out of the now-frozen cache and
     * surfacing a playback error — 30-40s after the pump actually gave up, per the buffered-edge gap
     * documented in BACKLOG.md ("Rebuild the session before the buffer starves"). Centralising the
     * flag write here is what makes {@link SabrSessionHolder#notifyFatal()} fire at the true moment
     * of failure instead of not at all.
     */
    private void markFatal() {
        fatal = true;
        holder.notifyFatal();
    }

    /** A reader is blocked on an evicted segment behind the buffered edge (backward seek). Ask the
     * loop to reposition the session onto it so the server re-sends from there. */
    void requestRefetchFrom(@NonNull final SabrSegmentRequest request) {
        pendingRefetch = request;
        ensureStarted();
    }

    /** A reader is blocked on a segment far ahead of the buffered edge (distant forward seek). Ask the
     * loop to jump the session's play head onto it so the server streams fresh from the target. */
    void requestForwardJumpTo(@NonNull final SabrSegmentRequest request) {
        pendingForwardJump = request;
        ensureStarted();
    }

    private void loop() {
        // Wall-clock start of the current unbroken run of IOExceptions; 0 when not currently in one.
        // See MAX_IO_ERROR_STREAK_MS above.
        long ioErrorStreakStartMs = 0;
        try {
            while (!stopped) {
                if (System.currentTimeMillis() - lastReadMs > IDLE_STOP_MS || session.isComplete()) {
                    break;
                }
                boolean ioErrorThisIteration = false;
                try {
                    // Cold-start prime (one-shot): if the session was built already seeked far forward
                    // (quality switch / resumed playback), make the session's FIRST request target that
                    // position so the server's first burst begins there. Bursting from 0 and then doing
                    // a distant forward-jump to the target stalls (the post-burst session state fights
                    // the jump). Doing it as the very first request keeps the session state clean.
                    if (!primed) {
                        primed = true;
                        final long startMs = holder.getStartPositionMs();
                        if (startMs > 0) {
                            final int seq = session.getStreamState()
                                    .getSegmentNumberAtOrAfterTimeMs(holder.videoFormat, startMs);
                            if (DEBUG) {
                                Log.i(TAG, "priming cold session to startMs=" + startMs
                                        + " seq=" + seq);
                            }
                            session.prepareForMediaSegment(SabrSegmentRequest.media(holder.videoFormat, seq));
                            session.pumpOnce(localization);
                            continue;
                        }
                    }
                    // Drive off what the player has ACTUALLY read, not the play head: the play head
                    // freezes while buffering and that deadlocked the pump. readerHead = furthest
                    // track read; readerTail = slowest track read (safe to evict below).
                    final long readerHeadMs = holder.getReaderHeadMs();
                    // Evict what both tracks have read past, EVERY round (or a full cache never drains
                    // and the throttle latches forever -> freeze), keeping BACK_BUFFER_MS behind the
                    // reader so a short backward seek finds its segments cached. But when the cache is
                    // already over the byte budget (high bitrate), shrink the back-buffer so eviction
                    // can actually drain it, otherwise the pump throttles forever and playback stalls.
                    final long backBufferMs = session.getCachedBytes() > MAX_AHEAD_BYTES
                            ? MIN_BACK_BUFFER_MS : BACK_BUFFER_MS;
                    session.setPlayHeadMs(Math.max(0, holder.getReaderTailMs() - backBufferMs));
                    session.evictPlayed();
                    final long edgeMs = session.getStreamState().getMinBufferedEndMs();
                    // Backward seek beyond the back-buffer: a reader is blocked on an evicted segment
                    // behind the edge. Reposition the session onto it (prepareForMediaSegment sets
                    // buffered=up-to-(seg-1) + playerTime=seg start, so the server re-sends from there)
                    // instead of fetching forward this round. Bypasses the throttle by design.
                    final SabrSegmentRequest refetch = pendingRefetch;
                    if (refetch != null) {
                        pendingRefetch = null;
                        final boolean sameTarget = lastRewindTarget != null
                                && lastRewindTarget.getFormat().getItag() == refetch.getFormat().getItag()
                                && lastRewindTarget.isInitializationSegment() == refetch.isInitializationSegment()
                                && lastRewindTarget.getSequenceNumber() == refetch.getSequenceNumber();
                        consecutiveRewindRepeats = sameTarget ? consecutiveRewindRepeats + 1 : 1;
                        lastRewindTarget = refetch;
                        if (consecutiveRewindRepeats > MAX_CONSECUTIVE_REWIND_REPEATS) {
                            Log.w(TAG, "SABR pump: rewind target itag=" + refetch.getFormat().getItag()
                                    + " seq=" + refetch.getSequenceNumber() + " repeated "
                                    + consecutiveRewindRepeats + "x without progress -- giving up");
                            markFatal();
                            break;
                        }
                        if (DEBUG) {
                            Log.i(TAG, "rewind to seq=" + refetch.getSequenceNumber()
                                    + " (edge=" + edgeMs + " readerHead=" + readerHeadMs + ")");
                        }
                        // Without this, a re-fetch of a segment that's still cache-resident (the
                        // expected case for a same-target retry) is a pure no-op: ingestAndCacheSegment
                        // discards newly-arrived bytes via putIfAbsent when the key is already cached.
                        session.discardCachedSegment(refetch);
                        session.prepareForRewind(refetch);
                        session.pumpOnce(localization);
                        continue;
                    }
                    // Distant forward seek: a reader is blocked on a segment far ahead of the edge.
                    // Jump the play head onto it (prepareForMediaSegment sets playerTime=seg start +
                    // assumes buffered up to seg-1, so the server streams fresh from the target)
                    // instead of dripping every intervening segment. Bypasses the throttle by design.
                    final SabrSegmentRequest jump = pendingForwardJump;
                    if (jump != null) {
                        pendingForwardJump = null;
                        if (DEBUG) {
                            Log.i(TAG, "forward-jump to seq=" + jump.getSequenceNumber()
                                    + " (edge=" + edgeMs + " readerHead=" + readerHeadMs + ")");
                        }
                        // prepareForForwardJump (not prepareForMediaSegment): moves the buffered head
                        // onto the target so the server streams from there + drops the stale span,
                        // instead of only extending maxSegment (which left the edge behind → the pump
                        // kept re-filling the skipped span). Ported from PipePipeExtractor PR #69.
                        session.prepareForForwardJump(jump);
                        session.pumpOnce(localization);
                        continue;
                    }
                    // Pace against the SLOWEST-read track, not the fastest. edgeMs is
                    // getMinBufferedEndMs() (a min) while getReaderHeadMs() is max(audio, video) —
                    // comparing a min against a max systematically understates how far ahead we
                    // are, so the throttle almost never fires. Audio decodes cheaply and races
                    // ahead; video crawls on the software decoder; the gap the throttle measures
                    // stays near zero while the real read-ahead grows without bound. Field run 12:
                    // playback at ~4:38 with the buffered edge at ~8:29 — 231s of read-ahead
                    // against a 30s cushion, ~8x over budget.
                    //
                    // getReaderTailMs() is min(audio, video) and returns 0 until both tracks have
                    // read something, so fall back to the head during cold start to keep the
                    // initial fill behaviour unchanged.
                    final long readerTailMs = holder.getReaderTailMs();
                    final long paceRefMs = readerTailMs > 0 ? readerTailMs : readerHeadMs;
                    final boolean throttled = edgeMs - paceRefMs > READAHEAD_CUSHION_MS
                            || session.getCachedBytes() > MAX_AHEAD_BYTES;
                    if (throttled) {
                        Thread.sleep(IDLE_POLL_MS);
                        continue;
                    }
                    // Report the CONTIGUOUS buffered edge. The server fills FROM the reported
                    // position, and upstream's getPlayerTimeMs() reports
                    // max(audio.bufferedEnd, video.bufferedEnd) — the FURTHEST track. Audio segments
                    // here are ~10s and video ~3-7s, so audio is routinely further ahead, and
                    // reporting the max makes the server resume video from audio's edge and skip the
                    // video segments in between. The field log shows exactly that: video arriving as
                    // 1,2,3,4 → 6,7,8 → 11..15 → 17..20 (holes at 5, 9-10, 16), then a reader hitting
                    // a hole and forcing a rewind to seq 4. That thrash is what starved playback and
                    // walked the quality ladder 480→360→240→144.
                    //
                    // (The look-ahead floor that briefly lived here is gone for good — run 8 showed
                    // the theory behind it was wrong. This is the older, separately-motivated
                    // contiguous-edge report, which was correct and which the refresh dropped.)
                    //
                    // Safe to set unconditionally: YoutubeSabrStreamState.getRequestPlayerTimeMs()
                    // checks the cold-start init gate BEFORE this override (a local reordering vs
                    // upstream, documented there), so #80 still holds during startup.
                    session.getStreamState().setPlayerTimeMs(edgeMs);
                    final List<SabrMediaSegment> segments = session.pumpOnce(localization);
                    // The server's answer to response n=0 is the session's prognosis: status 2
                    // (grace) means a fixed ~60s burst and then status 3 forever. Tell the
                    // view-model NOW so it can rebuild against a fresh session + token, rather than
                    // letting the burst play out and surfacing the failure minutes later through
                    // ExoPlayer. The pump keeps running meanwhile — the grace media is real and
                    // there is no reason to stall playback while the replacement is built.
                    if (session.getFirstResponseProtectionStatus() == PROTECTION_GRACE) {
                        holder.notifyGraceSession();
                        if (session.getLastResponseProtectionStatus() == PROTECTION_PROTECTED) {
                            // The grace burst is spent. What happens next, left alone, is the
                            // server's 10-retry budget followed by two in-session reloads — 78.6s
                            // in run 17 — and NONE of it has ever recovered a grace session. What
                            // has recovered one, twice out of twice, is a full player rebuild. So
                            // stop here and let that happen instead of grinding through machinery
                            // with no observed success.
                            //
                            // Guarded on the session having OPENED in grace: a session that opened
                            // at status 1 and hits a transient 3 is a different animal, and the
                            // policy's re-mint/reload path is the right response for it.
                            Log.w(TAG, "grace session went protected — abandoning for a rebuild "
                                    + "instead of spending the retry/reload budget");
                            markFatal();
                            break;
                        }
                    }
                    if (segments.isEmpty()) {
                        Thread.sleep(IDLE_POLL_MS);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (final IOException e) {
                    ioErrorThisIteration = true;
                    final long now = System.currentTimeMillis();
                    if (ioErrorStreakStartMs == 0) {
                        ioErrorStreakStartMs = now;
                    } else if (now - ioErrorStreakStartMs > MAX_IO_ERROR_STREAK_MS) {
                        Log.w(TAG, "SABR pump: I/O errors persisted for "
                                + (now - ioErrorStreakStartMs) + "ms, giving up: " + e.getMessage());
                        markFatal();
                        // Same recovery path as the ExtractionException case below: the ViewModel
                        // rebuilds a fresh session/token on the resulting playback error + re-fetch —
                        // or, now, on the proactive SabrSessionHolder#notifyFatal hand-off if it wins
                        // the race against the buffer running dry. See markFatal()'s doc.
                        break;
                    }
                    sleepQuietly(ERROR_RETRY_MS);
                } catch (final ExtractionException e) {
                    Log.w(TAG, "SABR pump fatal: " + e.getMessage());
                    markFatal();
                    // The session is dead (token/state). Stop; the player view-model rebuilds a fresh
                    // holder (new session/token) on the resulting playback error + re-fetch — or the
                    // proactive hand-off above, if it wins the race. See markFatal()'s doc.
                    break;
                }
                if (!ioErrorThisIteration) {
                    ioErrorStreakStartMs = 0;
                }
            }
        } finally {
            synchronized (this) {
                stopped = true;
                // Publish "this thread is really gone" so ensureStarted() may start the next one. Only
                // clear the slot if it still points at us: never strand a newer thread's registration.
                if (thread == Thread.currentThread()) {
                    thread = null;
                }
            }
        }
    }

    private static void sleepQuietly(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
