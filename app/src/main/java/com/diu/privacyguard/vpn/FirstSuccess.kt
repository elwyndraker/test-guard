package com.diu.privacyguard.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** One way of getting an answer, started [startAfterMs] after the race begins. */
class Attempt<T>(val startAfterMs: Long, val label: String, val run: suspend () -> T?)

data class Outcome<T>(val value: T, val label: String)

/**
 * Runs [attempts] as a staggered race and returns the first non-null result.
 *
 * Each attempt starts after its delay, or immediately once any attempt has failed, so a broken
 * route never makes the others wait. Attempts run in [scope], not inside this call, so a loser
 * stuck in a blocking socket read doesn't delay the winner. Returns null if all attempts fail
 * or [timeoutMs] passes first.
 */
suspend fun <T> firstSuccess(
    scope: CoroutineScope,
    attempts: List<Attempt<T>>,
    timeoutMs: Long,
): Outcome<T>? {
    if (attempts.isEmpty()) return null
    val winner = CompletableDeferred<Outcome<T>?>()
    val anyFailed = CompletableDeferred<Unit>()
    val remaining = AtomicInteger(attempts.size)

    val jobs = attempts.map { attempt ->
        scope.launch {
            if (attempt.startAfterMs > 0) withTimeoutOrNull(attempt.startAfterMs) { anyFailed.await() }
            if (winner.isCompleted) return@launch
            val value = try {
                attempt.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (value != null) {
                winner.complete(Outcome(value, attempt.label))
            } else {
                anyFailed.complete(Unit)
                if (remaining.decrementAndGet() == 0) winner.complete(null)
            }
        }
    }

    val result = withTimeoutOrNull(timeoutMs) { winner.await() }
    jobs.forEach { it.cancel() }
    return result
}
