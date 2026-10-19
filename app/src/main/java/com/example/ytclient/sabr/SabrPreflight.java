package com.example.ytclient.sabr;

import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

/**
 * Decide whether a freshly built SABR session is worth handing to the player, BEFORE the player
 * exists.
 *
 * <h3>Why</h3>
 * The opening {@code StreamProtectionStatus} decides a session's whole fate: one that opens at 2
 * gets a fixed ~1.08 MiB of media and then answers 3 forever, and no amount of re-minting recovers
 * it. Runs 15-19 have never produced a counterexample. Rebuilding is the only thing that has ever
 * worked, and it works often — run 18 got 2, 2, then 1 on three token-identical sessions.
 *
 * <p>Until now that rebuild was <em>reactive</em>: the pump discovered the status, called back into
 * the view-model, and the view-model tore down and rebuilt the whole ExoPlayer. Run 18 paid three
 * {@code ExoPlayerImpl Init}/{@code Release} cycles and ~3.4 s of visible startup for what is
 * really a 4 ms decision. This class moves the decision in front of the player: probe the session
 * with its own first request(s), and only publish a holder once the answer is known.
 *
 * <h3>Why probing does not waste the media it fetches</h3>
 * {@link YoutubeSabrSession#pumpOnce} caches its segments inside the session — {@code
 * SabrStreamPump}'s loop discards the returned list and only checks whether it was empty, because
 * the data sources read from that cache. So a probe on a session that turns out to be GOOD is not
 * overhead: those bytes are the same bytes playback was about to ask for, already in hand. A probe
 * on a session that turns out to be BAD costs one request that the old code would have made anyway.
 */
public final class SabrPreflight {

    private static final String TAG = "SabrPreflight";

    /** {@link org.schabi.newpipe.extractor.services.youtube.sabr.SabrStreamProtectionStatus}. */
    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_GRACE = 2;

    /**
     * How many requests the probe may issue while waiting for a protection status to appear.
     *
     * <p>Not 1. Run 19's response n=0 was a {@code SabrContextUpdate} round (parts 57/67) carrying
     * no media and no field 58 at all; the status only arrived with n=1. A probe that gave up after
     * one request would have called that session UNKNOWN and published it — which is exactly the
     * bug that let run 19 run to its death. Three leaves room for another interstitial round
     * without letting a pathological server stall startup.
     */
    private static final int MAX_PROBE_REQUESTS = 3;

    /**
     * Wall-clock ceiling for the whole probe. On expiry the session is published UNKNOWN and the
     * reactive bail-out remains as the net.
     *
     * <p>WAS 5_000, WHICH WAS BROKEN (run 20). The server's opening SabrContextUpdate round comes
     * with its own mandated {@code backoffMs} of 4000-4800 ms, which {@code pumpOnce} honours
     * internally by sleeping. A 5 s budget is therefore smaller than ONE mandated wait, so the
     * probe could not reliably reach the second request — which is where the protection status
     * lives. Run 20's first probe published `status=unknown after 1 attempt(s)` at 00:40:02.643,
     * its deadline to the millisecond, while the status arrived 0.58 s later on the player's own
     * pump. The probe then looked useless while actually being starved.
     *
     * <p>12 s covers the longest backoff observed (4.8 s) plus the request plus margin, and is
     * still bounded well under the ~25 s the server's own retry budget would burn.
     */
    private static final long BUDGET_MS = 12_000L;

    private SabrPreflight() {
    }

    /**
     * Issue up to {@link #MAX_PROBE_REQUESTS} requests on [holder]'s session until the server
     * states a protection status.
     *
     * <p>Never throws: a probe is an optimisation, and a session that errors here is handed on
     * as {@link #STATUS_UNKNOWN} so the existing playback-error and grace paths handle it exactly
     * as they did before. Runs on a background thread (it blocks on HTTP).
     *
     * @return {@link #STATUS_OK}, {@link #STATUS_GRACE}, the raw status, or {@link #STATUS_UNKNOWN}
     *         if the server never said within the budget.
     */
    public static int probe(@NonNull final SabrSessionHolder holder,
                            @NonNull final Localization localization) {
        final YoutubeSabrSession session = holder.session;
        final long deadline = System.currentTimeMillis() + BUDGET_MS;
        for (int i = 0; i < MAX_PROBE_REQUESTS; i++) {
            final int already = session.getFirstResponseProtectionStatus();
            if (already > 0) {
                return already;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                session.pumpOnce(localization);
            } catch (final Throwable t) {
                // One catch, not a chain: pumpOnce declares only IOException and ExtractionException,
                // so `catch (InterruptedException)` would be an unreachable-catch COMPILE error.
                // Interruption still reaches us — as InterruptedIOException from the blocking HTTP
                // call, or as an InterruptedException wrapped by something below — so test for it
                // here and restore the flag rather than swallowing it.
                if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return STATUS_UNKNOWN;
                }
                // A session that cannot complete its first request is not something the probe should
                // adjudicate — publish it and let the pump surface the failure through the normal
                // playback-error path.
                Log.w(TAG, "probe request failed, publishing session unjudged: " + t.getMessage());
                return STATUS_UNKNOWN;
            }
        }
        final int status = session.getFirstResponseProtectionStatus();
        return status > 0 ? status : STATUS_UNKNOWN;
    }

    /** True if [status] means the session is doomed and a rebuild is worth trying. */
    public static boolean shouldRebuild(final int status) {
        return status == STATUS_GRACE;
    }

    /** Human-readable form for logs. */
    @NonNull
    public static String describe(final int status) {
        switch (status) {
            case STATUS_OK:
                return "1 (ok)";
            case STATUS_GRACE:
                return "2 (grace)";
            case STATUS_UNKNOWN:
                return "unknown";
            default:
                return String.valueOf(status);
        }
    }
}
