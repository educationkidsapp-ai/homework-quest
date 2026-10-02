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
    override fun kind(): BiometricKind? {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)) return null
        return when (context.biometryType) {
            LABiometryTypeFaceID -> BiometricKind.FACE
            LABiometryTypeTouchID -> BiometricKind.FINGERPRINT
            else -> BiometricKind.GENERIC
        }
    }

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
