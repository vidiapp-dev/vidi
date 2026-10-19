package com.example.ytclient.core.perf

import android.os.SystemClock
import android.util.Log

/**
 * Cold-start stopwatch. Exists because every claim about startup cost in this repo's history has
 * been an inference from reading code, and the one time specific log contents were asserted from a
 * compaction summary they turned out to be wrong (see CLAUDE.md's evidence-over-inference rule).
 * This makes the critical path measurable instead of arguable.
 *
 * [t0] is stamped by [com.example.ytclient.App.onCreate] — as close to process start as app code
 * can get — and every [mark] is reported as a delta from it, so a single logcat filter on the
 * `Startup` tag reads as an ordered timeline of the cold-start path:
 *
 * ```
 * adb logcat -s Startup
 * ```
 *
 * Deliberately `Log.i`, not `Log.d`: per CLAUDE.md's logging-discipline section, anything that
 * needs to survive a default logcat view (which may filter below Info) must be at least Info.
 *
 * Marks are one-shot by name ([mark] ignores a repeat of a name it has already reported) so a
 * stage reached once per screen — a ViewModel that gets rebuilt on a config change, a feed that
 * re-fetches — doesn't bury the actual cold start under later noise. Nothing here is on a hot
 * path; it's a `volatile` read plus a string concat per distinct stage.
 */
object Startup {

    @Volatile
    private var t0: Long = 0L

    private val seen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Stamp the origin. Called once, first thing in `App.onCreate`. */
    fun begin() {
        t0 = SystemClock.elapsedRealtime()
        seen.clear()
        Log.i(TAG, "t0 — App.onCreate entered")
    }

    /** Report [stage] as a delta from [begin], once. Safe to call from any thread. */
    fun mark(stage: String) = report(stage, stage)

    /**
     * Time [body] and report it as `<stage> (took N ms)` alongside its own completion delta — for
     * the stages where "when did it finish" is less interesting than "how long did it hold the
     * thread", e.g. anything still running on the main thread during composition.
     *
     * Deliberately not `inline`: the duration goes in the MESSAGE but the dedupe key stays the bare
     * [stage], so a stage reached again later (a ViewModel rebuilt by a config change, say) is
     * still suppressed — which an inline version calling the public [mark] with an
     * already-interpolated string could not do. One lambda allocation per call, on a path that runs
     * a handful of times at startup.
     */
    fun <T> timed(stage: String, body: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        val result = body()
        report(stage, "$stage (took ${SystemClock.elapsedRealtime() - started} ms)")
        return result
    }

    private fun report(key: String, message: String) {
        val origin = t0
        if (origin == 0L) return // begin() hasn't run — nothing to measure against
        if (seen.putIfAbsent(key, true) != null) return
        Log.i(TAG, "+${SystemClock.elapsedRealtime() - origin} ms — $message")
    }

    private const val TAG = "Startup"
}
