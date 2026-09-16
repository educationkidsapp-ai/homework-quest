package quest.api.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * §4 feature flags, as the Admin's matrix screen sees them. The app only ever reads the flat
 * `{ key: boolean }` of `GET /schools/{id}/flags` (`ContentApi.schoolFlags`); everything here is dashboard-side.
 */

/** How far along a flag is; the matrix groups by it. */
@Serializable
enum class RolloutStage {
    @SerialName("internal") INTERNAL,
    @SerialName("beta") BETA,
    @SerialName("ga") GA,
}

/**
 * A flag as the platform defines it. [defaultOn] is what a school with no row of its own sees, so a new school
 * inherits the platform's defaults and a new flag reaches every school the moment it is seeded.
 */
@Serializable
data class FeatureFlagDefinition(
    val key: String,
    val description: String = "",
    val defaultOn: Boolean = false,
    val rolloutStage: RolloutStage = RolloutStage.INTERNAL,
    val createdAt: Long = 0,
)

/** One row of the matrix: every key with the value this school sees. */
@Serializable
data class SchoolFlags(
    val schoolId: String,
    val schoolName: String? = null,
    val flags: Map<String, Boolean> = emptyMap(),
)

/** `GET /admin/flags`: the definitions once, then a row per school the caller may see. */
@Serializable
data class FlagMatrix(
    val definitions: List<FeatureFlagDefinition> = emptyList(),
    val schools: List<SchoolFlags> = emptyList(),
)

/** The body of both `PUT /admin/schools/{id}/flags/{key}` and `PUT /admin/flags/{key}/all`. */
@Serializable
data class UpdateFlagRequest(val enabled: Boolean)

/**
 * A row of `flag_audit` (§4 "who flipped what and when"). [schoolId] null is the "for all schools" column action,
 * which is recorded once rather than per school.
 */
@Serializable
data class FlagAuditEntry(
    val id: String,
    val flagKey: String,
    val schoolId: String? = null,
    val schoolName: String? = null,
    val enabled: Boolean = false,
    val actorUserId: String? = null,
    val actorEmail: String? = null,
    val createdAt: Long = 0,
)
