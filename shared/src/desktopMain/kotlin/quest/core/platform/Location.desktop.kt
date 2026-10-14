package quest.core.platform

/**
 * Feature 15 on the desktop build: a fixed fix, so the check-in card can be clicked through without a phone.
 *
 * It answers the pilot school's own pin (Riyadh) with a 12 m accuracy, which is what a phone standing at the school
 * door reports — the server still decides whether that is inside the radius.
 */
class FakeLocationReader(
    private val fix: LocationFix = LocationFix(latitude = 24.7136, longitude = 46.6753, accuracyMeters = 12.0),
) : LocationReader {
    override suspend fun ensurePermission(): Boolean = true
    override suspend fun freshFix(timeoutMs: Long): LocationFix = fix
}
