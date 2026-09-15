package quest.api.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.Subject

/**
 * The Schools Dashboard contract (§2 tenancy, §5 roles). Kept apart from `AdminApi` on purpose: `webAdmin/`
 * implements that interface and must keep compiling until the Angular dashboard replaces it.
 */

/** Who a dashboard user is. Parents are not here — they stay in Firebase Auth. */
@Serializable
enum class Role {
    /** Platform owner: `schoolId` is null, sees every school and scopes with the `X-School-Id` header. */
    @SerialName("ADMIN") ADMIN,
    @SerialName("TEACHER") TEACHER,
    @SerialName("MANAGERIAL") MANAGERIAL,
}

@Serializable
enum class UserStatus { @SerialName("active") ACTIVE, @SerialName("disabled") DISABLED, @SerialName("invited") INVITED }

@Serializable
enum class SchoolStatus { @SerialName("active") ACTIVE, @SerialName("suspended") SUSPENDED }

/** One tenant. `theme` and `featureFlags` are raw JSON strings until the flags/themes package (P2.1) types them. */
@Serializable
data class School(
    val id: String,
    val name: String,
    val code: String,                                   // 6 characters, what a parent types to join
    val curriculumOptions: List<Curriculum> = emptyList(),
    val gradeOptions: List<Int> = emptyList(),
    val theme: String? = null,
    val featureFlags: String = "{}",
    val status: SchoolStatus = SchoolStatus.ACTIVE,
    val createdAt: Long = 0,
)

/** A school in a list: no theme, no flags. */
@Serializable
data class SchoolSummary(
    val id: String,
    val name: String,
    val code: String,
    val status: SchoolStatus = SchoolStatus.ACTIVE,
    val teachers: Int = 0,
    val children: Int = 0,
    val lessons: Int = 0,
)

/** What a parent sees after typing a school code, before confirming (§2). */
@Serializable
data class JoinSchoolInfo(
    val name: String,
    val logoUrl: String? = null,
    val curriculumOptions: List<Curriculum> = emptyList(),
    val gradeOptions: List<Int> = emptyList(),
)

/** Per school: (curriculum, grade, subject) with the teacher who owns it. Replaces the global `Course` for publishing. */
@Serializable
data class SchoolClass(
    val id: String,
    val schoolId: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val teacherId: String? = null,
    val teacherName: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class DashboardUser(
    val id: String,
    val email: String,
    val role: Role,
    val schoolId: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val language: String = "en",
    val mustChangePassword: Boolean = false,
    val lastLoginAt: Long? = null,
    val createdAt: Long = 0,
)

/** The public half of a teacher account: what the teacher island and the school page show. */
@Serializable
data class TeacherProfile(
    val userId: String,
    val displayName: String,
    val photoUrl: String? = null,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val grades: List<Int> = emptyList(),
    val bioEn: String? = null,
    val bioAr: String? = null,
)

/** A one-time email invite into a school (Admin invites Managerial, Managerial invites Teachers). */
@Serializable
data class Invite(
    val id: String,
    val email: String,
    val role: Role,
    val schoolId: String? = null,
    val invitedBy: String,
    val expiresAt: Long,
    val acceptedAt: Long? = null,
    val createdAt: Long = 0,
)

/** `POST /admin/auth/sign-in` (and, from P1.3, `POST /auth/sign-in`). */
@Serializable
data class SignInResponse(
    val token: String,
    val email: String,
    val expiresAt: Long,
    val role: Role = Role.ADMIN,
    val schoolId: String? = null,
    val displayName: String? = null,
    val mustChangePassword: Boolean = false,
)
