package quest.core.platform

/** A desktop build has no biometric prompt: the lock is never offered there. */
actual fun platformBiometricAuthenticator(): BiometricAuthenticator = object : BiometricAuthenticator {
    override fun kind(): BiometricKind? = null
    override suspend fun authenticate(reason: String): BiometricResult = BiometricResult.UNAVAILABLE
}
