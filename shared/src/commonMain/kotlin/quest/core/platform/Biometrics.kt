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
