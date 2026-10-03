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
    private var activeContext: LAContext? = null

    /**
     * Face ID / Touch ID while usable; [BiometricKind.PASSCODE] while it is not — none enrolled, locked out after
     * failed attempts, or switched off for this app in Settings — but a passcode is set; null with no passcode.
     */
    override fun kind(): BiometricKind? {
        val context = LAContext()
        val hasBiometrics = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)
        if (hasBiometrics) {
            return when (context.biometryType) {
                LABiometryTypeFaceID -> BiometricKind.FACE
                LABiometryTypeTouchID -> BiometricKind.FINGERPRINT
                else -> BiometricKind.GENERIC
            }
        }
        val hasPasscode = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
        return if (hasPasscode) BiometricKind.PASSCODE else null
    }

    /**
     * The device-owner policy: Face ID / Touch ID when usable, with device passcode fallback on physical devices.
     * When device passcode is not configured for LocalAuthentication (e.g. in iOS Simulator with Face ID enrolled),
     * falls back to evaluating [LAPolicyDeviceOwnerAuthenticationWithBiometrics] so biometrics can still be tested and used.
     */
    override suspend fun authenticate(reason: String): BiometricResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = LAContext()
        activeContext = context

        val canBiometrics = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)
        val canPasscode = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)

        if (!canBiometrics && !canPasscode) {
            activeContext = null
            return@withContext BiometricResult.UNAVAILABLE
        }

        val policy = if (canPasscode) LAPolicyDeviceOwnerAuthentication else LAPolicyDeviceOwnerAuthenticationWithBiometrics

        try {
            suspendCancellableCoroutine { continuation ->
                context.evaluatePolicy(policy, localizedReason = reason) { success, error ->
                    if (error != null) {
                        platform.Foundation.NSLog("Biometric authentication ended with error: code=%ld, localizedDescription=%@", error.code, error.localizedDescription)
                    }
                    if (continuation.isActive) {
                        continuation.resume(if (success) BiometricResult.SUCCESS else BiometricResult.CANCELLED)
                    }
                }
                continuation.invokeOnCancellation {
                    context.invalidate()
                }
            }
        } finally {
            if (activeContext === context) {
                activeContext = null
            }
        }
    }
}

actual fun elapsedRealtimeMillis(): Long = (platform.posix.clock_gettime_nsec_np(platform.posix.CLOCK_MONOTONIC_RAW.toUInt()) / 1_000_000uL).toLong()

/** iOS has no back gesture that reaches the app's root. */
actual fun sendAppToBackground() = Unit
