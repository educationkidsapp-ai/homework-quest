package quest.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The while-in-use location permission, asked for from the activity.
 *
 * `BiometricHost`'s shape, for the same reason: the shared code has no activity to launch a permission request from,
 * so `MainActivity` registers its `ActivityResultLauncher` here while it is alive. Nothing is remembered between
 * launches and no background permission is ever named — [AndroidLocationReader] asks for `ACCESS_FINE_LOCATION` and
 * `ACCESS_COARSE_LOCATION` and nothing else.
 */
object LocationPermissionHost {
    private var requester: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

    /** [request] launches the system dialog for those permissions and calls back with "any of them granted". */
    fun attach(request: (Array<String>, (Boolean) -> Unit) -> Unit) { requester = request }

    fun detach() { requester = null }

    internal fun ask(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val request = requester
        if (request == null) onResult(false) else request(permissions, onResult)
    }
}

/**
 * One fix per tap, from `LocationManager` through `androidx.core`.
 *
 * `LocationManagerCompat.getCurrentLocation` is a single-shot request — there is no listener left registered and
 * nothing to forget to unregister — and it is the platform API rather than `play-services-location` so the APK gains
 * no dependency for one call a day. GPS is asked first and the network provider is the fallback: indoors at a school
 * door the network fix is often the only one that arrives, and the server refuses it anyway when its accuracy is
 * worse than 100 m.
 */
class AndroidLocationReader(private val context: Context) : LocationReader {

    private val manager: LocationManager? get() = ContextCompat.getSystemService(context, LocationManager::class.java)

    private fun held(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun ensurePermission(): Boolean {
        if (held()) return true
        val granted = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                LocationPermissionHost.ask(PERMISSIONS) { if (continuation.isActive) continuation.resume(it) }
            }
        }
        // The dialog's answer and the permission we actually hold can disagree (a "don't ask again" launch answers
        // immediately), so the grant is re-read rather than trusted.
        return granted && held()
    }

    override suspend fun freshFix(timeoutMs: Long): LocationFix? {
        if (!held()) return null
        val manager = manager ?: return null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null
        return current(manager, LocationManager.GPS_PROVIDER, timeoutMs)
            ?: current(manager, LocationManager.NETWORK_PROVIDER, minOf(timeoutMs, NETWORK_TIMEOUT_MS))
    }

    private suspend fun current(manager: LocationManager, provider: String, timeoutMs: Long): LocationFix? {
        if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) return null
        val executor = Executor { it.run() }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                        if (continuation.isActive) continuation.resume(location?.asFix())
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun Location.asFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        // A provider that reports no accuracy is reported as "worse than anything", which the server refuses.
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE,
        mocked = isMocked(),
    )

    @Suppress("DEPRECATION")
    private fun Location.isMocked(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider

    private companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        const val NETWORK_TIMEOUT_MS = 5_000L
    }
}
