package quest.core.platform

/** What the device unlocks with — only so the copy can name it ("Face ID", "fingerprint"). */
enum class BiometricKind { FACE, FINGERPRINT, GENERIC }

/** [CANCELLED] covers every way a prompt ends without the owner being confirmed: dismissed, failed, locked out. */
enum class BiometricResult { SUCCESS, CANCELLED, UNAVAILABLE }

/**
 * The device's own "is this the owner?" prompt: Face ID / Touch ID on iOS, `BiometricPrompt` on Android, each with the
 * device passcode as the system's fallback. It confirms presence and nothing else — no key, no token and no secret
 * passes through it; the session stays where `AuthProvider` keeps it.
 */
interface BiometricAuthenticator {
    /** Null when the device has no biometric hardware, none is enrolled, or the platform has no prompt at all. */
    fun kind(): BiometricKind?

    /** Shows the system prompt with [reason] and suspends until it ends. Never throws. */
    suspend fun authenticate(reason: String): BiometricResult
}

expect fun platformBiometricAuthenticator(): BiometricAuthenticator

/**
 * Milliseconds on a clock that only moves forward and **keeps counting while the device is asleep** — what "more than
 * a minute away" is measured on. The wall clock is no use for that: the owner (or a child) can set it back.
 * Android: `SystemClock.elapsedRealtime()`. iOS: `clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)`, which is
 * `mach_continuous_time` — unlike `ProcessInfo.systemUptime`, it advances during sleep.
 */
expect fun elapsedRealtimeMillis(): Long

/** What the system back gesture does on the lock's cover: the app goes to the background; the cover is not dismissed. */
expect fun sendAppToBackground()
