package quest.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending work: a [CancellationException] is rethrown rather than turned into a failed [Result].
 *
 * Plain `runCatching` catches `Throwable`, so a coroutine that is being cancelled "recovers" from its own cancellation
 * and carries on doing work nobody is waiting for — a refresh that keeps writing to the device after the screen has
 * gone, or a `while (true)` sync loop that will not stop. Everything else is still swallowed, which is the point at a
 * call site whose whole contract is "on failure, keep what we have".
 */
inline fun <T> runCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
