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
 * Face ID / Touch ID through `LAContext`, the device passcode as the system's own fallback. The *biometric* policy
 * decides how the lock is named; the device-owner policy — the same sheet, passcode included — decides whether it can
 * be offered at all, so an iPhone with a passcode and no usable Face ID is offered its passcode (M6).
 */
actual fun platformBiometricAuthenticator(): BiometricAuthenticator = object : BiometricAuthenticator {
    override val lockToSetUp = BiometricKind.PASSCODE

    /**
     * Face ID / Touch ID while usable; [BiometricKind.PASSCODE] while it is not — none enrolled, locked out after
     * failed attempts, or switched off for this app in Settings — but a passcode is set; null with no passcode.
     */
    override fun kind(): BiometricKind? {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)) {
            return if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) BiometricKind.PASSCODE else null
        }
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
