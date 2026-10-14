package quest.feature.staff.domain

/**
 * Feature 15's three facts that live on the device rather than on the server: this install's id, whether the privacy
 * rationale has been read, and which language to word the biometric prompt in.
 *
 * It is a port and not `SettingsStore` itself because `ArchitectureTest` keeps `quest.core.db` out of every
 * presentation package — and because the install id is the thing a device binding hangs on, which is worth being able
 * to hand a test a fixed one.
 */
interface CheckInPrefs {
    /** This install's own random id, generated the first time it is asked for and kept from then on. */
    suspend fun installId(): String

    suspend fun rationaleSeen(): Boolean
    suspend fun markRationaleSeen()

    /** `en` | `ar`: the system prompt is the one thing feature 15 words outside the Compose tree. */
    fun language(): String
}
