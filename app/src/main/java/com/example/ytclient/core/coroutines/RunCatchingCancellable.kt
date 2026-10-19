package com.example.ytclient.core.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but re-throws [CancellationException] instead of wrapping it in a failed
 * [Result].
 *
 * Plain `runCatching { suspendCall() }` (or `try { suspendCall() } catch (t: Throwable) { ... }`)
 * treats coroutine cancellation as just another exception to swallow — a cancelled coroutine
 * should stop, not keep running past the catch with a "failed" result as if the call had thrown
 * a normal error. Left unchecked this breaks structured concurrency: a `viewModelScope` job torn
 * down mid-call (screen navigated away, a newer request superseding an older one, etc.) can end
 * up completing anyway — retry logic firing again, state getting written after the caller no
 * longer cares, and so on.
 *
 * Use this (or an explicit `catch (ce: CancellationException) { throw ce }` ahead of a broader
 * catch, when the surrounding shape doesn't fit a single expression) anywhere the guarded block
 * suspends. It's a no-op for every other exception — [Result.failure] still gets a normal
 * [Throwable] exactly as `runCatching` would produce.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
