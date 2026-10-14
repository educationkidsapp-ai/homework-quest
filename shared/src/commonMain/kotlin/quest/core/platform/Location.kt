package quest.core.platform

/**
 * Feature 15: one fix, read at the moment the employee taps.
 *
 * [accuracyMeters] is the radius the platform claims around the point (Android's `Location.accuracy`, iOS's
 * `horizontalAccuracy`); the server refuses anything worse than `CheckInRules.MAX_ACCURACY_METERS`. [mocked] is
 * Android's mock-location flag, forwarded untouched — the decision is the server's, never the app's.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val mocked: Boolean = false,
)

/**
 * The phone's location, **while the app is in use only**. There is deliberately no "start updates" and no background
 * permission anywhere in this app: the only thing that reads a coordinate is a tap on Check in, the fix goes straight
 * into one request, and the server throws the coordinates away once it has the distance
 * (docs/features/15-geofence-check-in.md, "Privacy").
 */
interface LocationReader {
    /**
     * Asks for the while-in-use permission if it is not held yet and answers whether it is held now. Never throws;
     * false is "no fix can be taken", whatever the reason.
     */
    suspend fun ensurePermission(): Boolean

    /**
     * One fresh, high-accuracy fix, or null when none arrives inside [timeoutMs] — a weak or missing fix is a state
     * the screen words ("try again"), not an exception.
     */
    suspend fun freshFix(timeoutMs: Long = 15_000): LocationFix?
}

/** Where there is no location hardware to ask (desktop) or no host attached yet: no permission, no fix. */
object NoLocationReader : LocationReader {
    override suspend fun ensurePermission(): Boolean = false
    override suspend fun freshFix(timeoutMs: Long): LocationFix? = null
}
