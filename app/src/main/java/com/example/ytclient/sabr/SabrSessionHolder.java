package com.example.ytclient.sabr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlinx.coroutines.Job;

/**
 * Bundle of a {@link YoutubeSabrSession} and its selected formats for one video, plus the per-track
 * reader bookkeeping the {@link SabrStreamPump} and eviction run on. The audio and video
 * {@link SabrSegmentDataSource}s drive the SAME session (one SABR response carries both formats), so
 * the session's segment cache serves both without doubling bandwidth.
 *
 * <p>Ported from PipePipeClient's {@code SabrSessionStore.Holder} (PR #47). The surrounding static
 * session store / probe / format-pick is intentionally dropped here: our JS runtime + {@link
 * SabrSessionFactory} already build the session and {@code WebPoTokenProvider} is already a
 * process-wide singleton, so the holder is constructed directly by the factory and owned by the
 * player view-model for the lifetime of the playback.</p>
 */
public final class SabrSessionHolder {

    @NonNull public final String videoId;
    @NonNull public final YoutubeSabrInfo info;
    @NonNull public final YoutubeSabrSession session;
    @NonNull public final YoutubeSabrFormat audioFormat;
    @NonNull public final YoutubeSabrFormat videoFormat;

    // Real playback position (ms); written by the player loop. Kept for reference but NOT used to
    // drive the pump/eviction: it freezes when the player buffers, which deadlocked everything.
    private volatile long playerTimeMs;
    // Where the pump should begin its VERY FIRST request (ms). 0 = start of stream (normal). Set >0
    // when a cold session is built already seeked far forward (quality switch / resumed playback):
    // the pump primes the session straight to here so the segment arrives in the first server burst,
    // instead of bursting from 0 and then needing a distant forward-jump that stalls. Read once.
    private volatile long startPositionMs;
    // What each track's data source has actually read (segment end ms). This is the truth the pump
    // and eviction run on: it never goes stale (a stalled reader sits on its last segment, so the
    // pump sees edge ~= readerHead and keeps feeding instead of pacing off a frozen play head).
    private final Map<Integer, Long> readerPositions = new ConcurrentHashMap<>();
    private volatile SabrStreamPump pump;
    // LOCAL ADDITION. Set once release() has run — see release()'s doc for the failure this guards
    // against. Distinct from SabrStreamPump's OWN `released` flag: that one protects an
    // ALREADY-CREATED pump from restarting, but does nothing if release() runs before this holder's
    // pump has ever been lazily created (pump == null). This flag closes that gap at the holder
    // level, and getPump() below is synchronized on the SAME monitor as release() so the two can't
    // interleave.
    private boolean released;
    // Grace-session bail-out: see setOnGraceSession. Written by the view-model, read+fired by the
    // pump thread, so both need to be safely published; the flag makes the callback one-shot.
    @Nullable private volatile Runnable onGraceSession;
    private final AtomicBoolean graceNotified = new AtomicBoolean();
    // LOCAL ADDITION. Fired once by SabrStreamPump the moment it marks itself fatal (grace gone
    // protected, a dead I/O streak, or a fatal ExtractionException — see SabrStreamPump.markFatal).
    // Existing behaviour left this reactive-only: the pump keeps serving whatever is already cached
    // while ExoPlayer plays out the buffer, then errors once it hits the absent segment — 30-40s
    // later in the field (see BACKLOG.md "Rebuild the session before the buffer starves"). This hook
    // lets the view-model start building a replacement session THE MOMENT the pump gives up, so the
    // handoff can land before the buffer actually runs dry instead of after ExoPlayer notices.
    @Nullable private volatile Runnable onFatal;
    private final AtomicBoolean fatalNotified = new AtomicBoolean();
    // LOCAL ADDITION. Set once the two-phase token upgrade (see SabrSessionFactory) has pushed a
    // content(videoId)-bound pot into this holder's live session. Read by a caller that reuses
    // this holder's pot to seed a NEW session (a quality/audio switch — see
    // PlayerViewModel.rebuildSabr) to decide whether the new session can skip the upgrade dance
    // (already content-bound) or must repeat it (still on the session-bound bootstrap pot).
    private final AtomicBoolean poTokenUpgraded = new AtomicBoolean();
    // LOCAL ADDITION. The fire-and-forget GlobalScope.launch that mints the content(videoId)-bound
    // upgrade pot (see SabrSessionFactory.create) — tracked ONLY so release() can cancel it. Before
    // this, a released holder's upgrade mint kept running to completion regardless (its
    // holder.applyUpgradedPoToken() call is a harmless no-op on a released holder, so nothing broke
    // correctness), but it kept occupying WebPoTokenProvider's single process-wide mintMutex for up
    // to GVS_MINT_TIMEOUT_MS after nobody could use the result any more. Under repeated fast SABR
    // retries (each fetchSabr() call starts a new one) these piled up and starved the NEXT retry's
    // own bootstrap mint — and, since DASH's legacy-playback po-token mint shares the same mutex,
    // starved that too. Cancelling here bounds the damage to "at most one in-flight mint per live
    // holder" instead of "one per retry, forever accumulating."
    @Nullable private volatile Job upgradeJob;

    // LOCAL ADDITION. Auxiliary best-effort work started alongside the session — currently the
    // watch-page experiment-flag fetch (see SabrSessionFactory's gvsSwapDeferred). Tracked for the
    // same reason as upgradeJob: without it a burst of SABR retries each leaves an uncancellable
    // HTTP GET running on the same OkHttp client the media POSTs use, for a session nobody can use
    // any more.
    @Nullable private volatile Job auxJob;

    public SabrSessionHolder(@NonNull final String videoId,
                             @NonNull final YoutubeSabrInfo info,
                             @NonNull final YoutubeSabrSession session,
                             @NonNull final YoutubeSabrFormat audioFormat,
                             @NonNull final YoutubeSabrFormat videoFormat) {
        this.videoId = videoId;
        this.info = info;
        this.session = session;
        this.audioFormat = audioFormat;
        this.videoFormat = videoFormat;
    }

    public long getPlayerTimeMs() {
        return playerTimeMs;
    }

    /** Report the real playback position (reference only; the pump paces off reader positions). */
    public void setPlayerTimeMs(final long playerTimeMs) {
        this.playerTimeMs = playerTimeMs;
    }

    public long getStartPositionMs() {
        return startPositionMs;
    }

    /** Set the pump's initial seek target (ms) BEFORE playback starts the pump. See field doc. */
    public void setStartPositionMs(final long startPositionMs) {
        this.startPositionMs = startPositionMs;
    }

    /** A data source reports how far it has read (last served segment end, ms). */
    public void setReaderPositionMs(final int itag, final long ms) {
        readerPositions.put(itag, ms);
    }

    /** This track's last-read position (segment end ms), or -1 if it hasn't read yet. Diagnostics. */
    public long getReaderPositionMs(final int itag) {
        final Long v = readerPositions.get(itag);
        return v != null ? v : -1L;
    }

    /** Furthest-read track: the pump keeps the buffered edge a cushion ahead of THIS. */
    public long getReaderHeadMs() {
        long head = 0;
        final Long a = readerPositions.get(audioFormat.getItag());
        final Long v = readerPositions.get(videoFormat.getItag());
        if (a != null) {
            head = Math.max(head, a);
        }
        if (v != null) {
            head = Math.max(head, v);
        }
        return head;
    }

    /** Slowest-read track: nothing before this is needed any more, so eviction starts here. Zero
     * until BOTH tracks have read something (else we'd evict the other track's unread segments). */
    public long getReaderTailMs() {
        final Long a = readerPositions.get(audioFormat.getItag());
        final Long v = readerPositions.get(videoFormat.getItag());
        if (a == null || v == null) {
            return 0;
        }
        return Math.min(a, v);
    }

    /**
     * Lazily create the single background pump that feeds both data sources for this video.
     *
     * <p>LOCAL FIX: if {@link #release()} already ran before any reader ever called this (teardown
     * racing a straggling first read — plausible during rapid video switching), the newly-created
     * pump is immediately released too, so its own {@code ensureStarted()} guard keeps it inert.
     * Without this, a pump created here AFTER release() had already checked {@code pump == null} and
     * found nothing to release would start fresh and never learn it should be dead — reproducing the
     * exact "session from a previous video still POSTing to GVS indefinitely" failure {@link
     * SabrStreamPump#release()} was built to prevent, just one layer up. Synchronizing this method
     * and {@link #release()} on the same (implicit {@code this}) monitor is what makes the check and
     * the creation atomic with respect to a concurrent release() call.</p>
     */
    synchronized SabrStreamPump getPump(@NonNull final Localization localization) {
        if (pump == null) {
            pump = new SabrStreamPump(session, this, localization);
            if (released) {
                pump.release();
            }
        }
        return pump;
    }

    /**
     * True once this holder's pump has already been created and has started serving segments (i.e.
     * this is NOT the holder's first player build). A warm holder already knows the stream's segment
     * timing and has real reader positions recorded from earlier playback, so a NEW player instance
     * reusing it (e.g. toggling captions) can seek directly to the resume point instead of starting at
     * 0 and warm-seeking forward — see the warmSeek usage in PlayerScreen. Requesting segment 0 on an
     * already-warm holder instead misreads as a backward seek (the reader positions are already far
     * into the video) and forces a wasteful rewind-then-forward-jump round trip.
     */
    public boolean isWarm() {
        return pump != null;
    }

    boolean isBeyondEnd(@NonNull final SabrSegmentRequest request) {
        return session.isBeyondEnd(request);
    }

    /**
     * Called at most once, from the pump thread, when response n=0 came back with
     * StreamProtectionStatus=2 (grace). EXPECTED now, not a failure signal: {@code
     * SabrSessionFactory} deliberately bootstraps every session on a fast session-bound token (see
     * its two-phase-bootstrap doc), which reads as grace by design, while the precisely
     * content-bound upgrade token mints in the background and gets pushed into this SAME live
     * session (see {@link #applyUpgradedPoToken}) the moment it's ready — no rebuild. The
     * view-model's listener is purely informational (logging) now; see {@code
     * PlayerViewModel.onGraceSession}.
     */
    public void setOnGraceSession(@Nullable final Runnable listener) {
        this.onGraceSession = listener;
    }

    /** Fire {@link #setOnGraceSession} exactly once. Called from the pump thread. */
    void notifyGraceSession() {
        if (graceNotified.compareAndSet(false, true)) {
            final Runnable listener = onGraceSession;
            if (listener != null) {
                listener.run();
            }
        }
    }

    /**
     * LOCAL ADDITION. Register a listener for {@link #notifyFatal}. See {@link #onFatal}'s field
     * doc for why this exists. Mirrors {@link #setOnGraceSession}'s threading contract exactly:
     * call from wherever the holder is wired up (view-model), fires later from the pump thread.
     */
    public void setOnFatal(@Nullable final Runnable listener) {
        this.onFatal = listener;
    }

    /** Fire {@link #setOnFatal} exactly once. Called from the pump thread, from
     *  {@link SabrStreamPump#markFatal()} — i.e. at most once per holder, the instant the pump
     *  gives up rather than whenever a caller later happens to check {@link SabrStreamPump#isFatal()}. */
    void notifyFatal() {
        if (fatalNotified.compareAndSet(false, true)) {
            final Runnable listener = onFatal;
            if (listener != null) {
                listener.run();
            }
        }
    }

    /**
     * LOCAL ADDITION. Push the videoId-bound upgrade token into this holder's live session — see
     * {@link org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession#upgradePoToken}.
     * Safe to call from any thread, including after {@link #release()} (harmless no-op then).
     */
    public void applyUpgradedPoToken(@NonNull final byte[] token) {
        session.upgradePoToken(token);
        poTokenUpgraded.set(true);
    }

    /** Whether {@link #applyUpgradedPoToken} has already run for this holder — see its doc. */
    public boolean isPoTokenUpgraded() {
        return poTokenUpgraded.get();
    }

    /** Mark this holder as already carrying an upgraded (content-bound) pot without re-pushing it
     *  — used when a NEW holder is seeded directly from an already-upgraded pot (see
     *  SabrSessionFactory's seedPoTokenUpgraded parameter), so it does not repeat the upgrade. */
    public void markPoTokenUpgraded() {
        poTokenUpgraded.set(true);
    }

    /**
     * LOCAL ADDITION. Record the background upgrade-mint {@link Job} so {@link #release()} can
     * cancel it — see {@link #upgradeJob}'s field doc. Called once, right after
     * {@code SabrSessionFactory.create} launches it. If this holder was already released by the
     * time the caller gets around to tracking the job (release() ran first), cancel it immediately
     * instead of leaving it to run — the same race {@link #getPump} already guards against, handled
     * the same way.
     */
    public synchronized void trackUpgradeJob(@NonNull final Job job) {
        this.upgradeJob = job;
        if (released) {
            job.cancel(null);
        }
    }

    /**
     * LOCAL ADDITION. Record auxiliary best-effort work so {@link #release()} can cancel it — see
     * {@link #auxJob}'s field doc. Same already-released race handling as {@link #trackUpgradeJob}.
     */
    public synchronized void trackAuxJob(@NonNull final Job job) {
        this.auxJob = job;
        if (released) {
            job.cancel(null);
        }
    }

    /**
     * Permanently stop the pump thread (playback teardown). Uses the pump's TERMINAL release, not
     * the revivable stop(): a straggling read from the departing player must not resurrect this
     * session — see SabrStreamPump.released.
     *
     * <p>LOCAL FIX: synchronized on the same monitor as {@link #getPump}, and sets {@link #released}
     * BEFORE checking the {@code pump} field, so a getPump() call that is concurrently racing this
     * one either fully happens-before (sees released and returns a pre-released pump) or fully
     * happens-after (sees released and creates one already-released) this method — never in between,
     * which is what previously let a lazily-created pump slip through un-released. See getPump()'s
     * doc for the failure this closes.</p>
     */
    public synchronized void release() {
        released = true;
        final SabrStreamPump p = pump;
        if (p != null) {
            p.release();
        }
        // LOCAL ADDITION — see upgradeJob's field doc. Cancel with a null cause: this is routine
        // teardown (video switch, retry, quality change), not a failure worth propagating.
        final Job job = upgradeJob;
        if (job != null) {
            job.cancel(null);
        }
        final Job aux = auxJob;
        if (aux != null) {
            aux.cancel(null);
        }
    }
}
