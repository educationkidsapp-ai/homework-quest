@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package quest.core.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

/**
 * Face ID / Touch ID through `LAContext`. Availability is asked of the *biometric* policy, so the lock is offered only
 * on a device that has one enrolled; the prompt itself uses the device-owner policy, which is the same sheet with the
 * device passcode as its fallback.
 */
actual fun platformBiometricAuthenticator(): BiometricAuthenticator = object : BiometricAuthenticator {
    /**
     * Null while a biometric cannot be used *at this moment* — none enrolled, locked out after failed attempts, or
     * switched off for this app in Settings. That only stops the lock being offered; an app that is already locked
     * stays locked and [authenticate] falls back to the passcode.
     */
    override fun kind(): BiometricKind? {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)) return null
        return when (context.biometryType) {
            LABiometryTypeFaceID -> BiometricKind.FACE
            LABiometryTypeTouchID -> BiometricKind.FINGERPRINT
            else -> BiometricKind.GENERIC
        }
    }

    /**
     * The device-owner policy: Face ID / Touch ID when usable, otherwise — locked out, switched off, removed — the
     * device passcode, on the same system sheet. [BiometricResult.UNAVAILABLE] only when the device has no passcode.
     */
    override suspend fun authenticate(reason: String): BiometricResult {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) return BiometricResult.UNAVAILABLE
        return suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { success, _ ->
                if (continuation.isActive) continuation.resume(if (success) BiometricResult.SUCCESS else BiometricResult.CANCELLED)
            }
            continuation.invokeOnCancellation { context.invalidate() }
        }
    }
}

actual fun elapsedRealtimeMillis(): Long = (platform.posix.clock_gettime_nsec_np(platform.posix.CLOCK_MONOTONIC_RAW.toUInt()) / 1_000_000uL).toLong()

/** iOS has no back gesture that reaches the app's root. */
actual fun sendAppToBackground() = Unit
