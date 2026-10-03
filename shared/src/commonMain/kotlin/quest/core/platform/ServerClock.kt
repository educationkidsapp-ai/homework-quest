package quest.core.platform

import kotlin.concurrent.Volatile

/**
 * M4 (D8): the server's clock, as well as this device can tell it — the device clock plus the offset seen on the last
 * response's `Date` header (one-second resolution, which is all an exam window needs). Until a response has been seen
 * it is the device clock. Used only where the app must stop *before* the server says no — the exam window — and the
 * server still has the last word: an answer it refuses is refused whatever this clock said.
 */
object ServerClock {
    @Volatile private var offsetMillis = 0L

    fun observe(serverMillis: Long, deviceMillis: Long = Today.epochMillis()) { offsetMillis = serverMillis - deviceMillis }

    fun now(): Long = Today.epochMillis() + offsetMillis
}
