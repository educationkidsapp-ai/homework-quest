package quest.core.platform

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The activity a prompt is shown from. `BiometricPrompt` needs a `FragmentActivity`, and the shared code has none to
 * hand over, so `MainActivity` registers itself here while it is alive. Weak, so a finished activity is never kept.
 */
object BiometricHost {
    private var activity = WeakReference<FragmentActivity>(null)
    /** The application, kept from the first attach: what the device can unlock with does not need an activity. */
    private var application: Context? = null
    fun attach(host: FragmentActivity) { activity = WeakReference(host); application = host.applicationContext }
    fun detach(host: FragmentActivity) { if (activity.get() === host) activity.clear() }
    internal fun current(): FragmentActivity? = activity.get()
    internal fun context(): Context? = activity.get() ?: application
}

/**
 * `androidx.biometric.BiometricPrompt` with the device PIN, pattern or password as the system's own fallback
 * (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, the one combination every supported API level accepts; on API 26–28 the
 * library itself shows the screen lock through `KeyguardManager` when no biometric is enrolled). The lock is offered on
 * every phone that has a screen lock: with a Class 2+ biometric it is named "fingerprint or face", without one it is
 * "your phone's screen lock".
 */
actual fun platformBiometricAuthenticator(): BiometricAuthenticator = object : BiometricAuthenticator {
    override val lockToSetUp = BiometricKind.SCREEN_LOCK

    /**
     * [BiometricKind.GENERIC] while a Class 2+ biometric can be used; [BiometricKind.SCREEN_LOCK] when it cannot —
     * none enrolled, only a Class 1 face unlock (most Samsung, Xiaomi, Oppo and Huawei phones), or the sensor locked
     * out — but the phone has a PIN, pattern or password; null only on a phone with no screen lock at all.
     * `canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)` is "the device is secure" on every API level from 26.
     */
    override fun kind(): BiometricKind? {
        val manager = BiometricManager.from(BiometricHost.context() ?: return null)
        // Android does not say which biometric the owner enrolled, so the copy stays generic there.
        return androidLockKind(
            biometric = manager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS,
            secure = manager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS,
        )
    }

    override suspend fun authenticate(reason: String): BiometricResult = withContext(Dispatchers.Main.immediate) {
        val host = BiometricHost.current() ?: return@withContext BiometricResult.UNAVAILABLE
        if (BiometricManager.from(host).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) return@withContext BiometricResult.UNAVAILABLE
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (continuation.isActive) continuation.resume(BiometricResult.SUCCESS) }
                // A single unrecognised attempt (onAuthenticationFailed) leaves the prompt open; only an error ends it.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (continuation.isActive) continuation.resume(BiometricResult.CANCELLED) }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(reason).setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

actual fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()

actual fun sendAppToBackground() { BiometricHost.current()?.moveTaskToBack(true) }
