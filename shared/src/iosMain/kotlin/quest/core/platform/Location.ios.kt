package quest.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * `CLLocationManager` with `requestWhenInUseAuthorization` and a single `requestLocation` per tap — the iPhone
 * counterpart of the Android reader, and like it never asking for "Always".
 *
 * **Untested.** Feature 15 is written for both platforms, but the iPhone build has no Apple Developer account behind
 * it yet (docs/features/15-geofence-check-in.md, "iPhone"), so this path has never run on a device.
 *
 * `mocked` is Android's flag. iOS has no equivalent an app may trust: a location simulated from Xcode or a GPX file
 * is reported through `sourceInformation` only on a real device, and the server refuses a mocked fix rather than the
 * app, so this reader reports what iOS tells it and nothing more.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationReader : LocationReader {

    private val manager = CLLocationManager().apply { desiredAccuracy = kCLLocationAccuracyBest }

    override suspend fun ensurePermission(): Boolean = withContext(Dispatchers.Main) {
        if (manager.authorizationStatus.granted()) return@withContext true
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) return@withContext false
        // The system dialog answers through the delegate; a timeout rather than a wait forever, because an app that
        // is backgrounded while the dialog is up never hears back.
        withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val delegate = AuthorizationDelegate { status ->
                    if (continuation.isActive) continuation.resume(status.granted())
                }
                manager.delegate = delegate
                continuation.invokeOnCancellation { manager.delegate = null }
                manager.requestWhenInUseAuthorization()
            }
        } ?: false
    }

    override suspend fun freshFix(timeoutMs: Long): LocationFix? = withContext(Dispatchers.Main) {
        if (!manager.authorizationStatus.granted()) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val delegate = FixDelegate { location ->
                    if (continuation.isActive) continuation.resume(location?.asFix())
                }
                manager.delegate = delegate
                continuation.invokeOnCancellation { manager.delegate = null }
                manager.requestLocation()
            }
        }
    }

    private fun CLLocation.asFix(): LocationFix? {
        // A negative horizontal accuracy means the coordinate is invalid.
        val accuracy = horizontalAccuracy
        if (accuracy < 0.0) return null
        return coordinate.useContents {
            LocationFix(latitude = latitude, longitude = longitude, accuracyMeters = accuracy, mocked = false)
        }
    }

    private fun CLAuthorizationStatus.granted(): Boolean =
        this == kCLAuthorizationStatusAuthorizedWhenInUse || this == kCLAuthorizationStatusAuthorizedAlways

    private class AuthorizationDelegate(private val onStatus: (CLAuthorizationStatus) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
            onStatus(manager.authorizationStatus)
        }
    }

    private class FixDelegate(private val onLocation: (CLLocation?) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            onLocation(didUpdateLocations.lastOrNull() as? CLLocation)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            onLocation(null)
        }
    }

    private companion object {
        const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}
