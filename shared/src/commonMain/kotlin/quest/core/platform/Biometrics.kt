package quest.core.platform

/**
 * What the device unlocks with — only so the copy can name it ("Face ID", "fingerprint or face", "your phone's screen
 * lock"). [SCREEN_LOCK] is an Android phone whose only usable unlock is its PIN, pattern or password: most phones'
 * face unlock is a Class 1 (convenience) biometric that apps cannot use, so the lock is opened with the screen lock.
 */
enum class BiometricKind { FACE, FINGERPRINT, GENERIC, SCREEN_LOCK }

/** [CANCELLED] covers every way a prompt ends without the owner being confirmed: dismissed, failed, locked out. */
enum class BiometricResult { SUCCESS, CANCELLED, UNAVAILABLE }

/**
 * The device's own "is this the owner?" prompt: Face ID / Touch ID on iOS, `BiometricPrompt` on Android, each with the
 * device passcode as the system's fallback. It confirms presence and nothing else — no key, no token and no secret
 * passes through it; the session stays where `AuthProvider` keeps it.
 */
interface BiometricAuthenticator {
    /**
     * Null when the device has nothing the lock can be opened with: no biometric and no screen lock (Android), no
     * biometric enrolled (iOS), or no prompt at all (desktop).
     */
    fun kind(): BiometricKind?

    /** Shows the system prompt with [reason] and suspends until it ends. Never throws. */
    suspend fun authenticate(reason: String): BiometricResult
}

expect fun platformBiometricAuthenticator(): BiometricAuthenticator

/**
 * M6 — what an Android phone's lock is opened with, from what `BiometricManager` answers: a Class 2+ biometric
 * ([biometric], `BIOMETRIC_WEAK`) first, otherwise the screen lock when the phone has one ([secure],
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`), otherwise nothing. Kept here, apart from the platform call, so every branch
 * is tested without a device.
 */
fun androidLockKind(biometric: Boolean, secure: Boolean): BiometricKind? = when {
    biometric -> BiometricKind.GENERIC
    secure -> BiometricKind.SCREEN_LOCK
    else -> null
}

/**
 * Milliseconds on a clock that only moves forward and **keeps counting while the device is asleep** — what "more than
 * a minute away" is measured on. The wall clock is no use for that: the owner (or a child) can set it back.
 * Android: `SystemClock.elapsedRealtime()`. iOS: `clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)`, which is
 * `mach_continuous_time` — unlike `ProcessInfo.systemUptime`, it advances during sleep.
 */
expect fun elapsedRealtimeMillis(): Long

/** What the system back gesture does on the lock's cover: the app goes to the background; the cover is not dismissed. */
expect fun sendAppToBackground()
