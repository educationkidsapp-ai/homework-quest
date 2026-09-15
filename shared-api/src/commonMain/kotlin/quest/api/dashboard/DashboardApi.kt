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
    /** Set on `GET /me` while an Admin is viewing the dashboard as this user (§5 "View as…"); the id of that Admin. */
    val impersonatedBy: String? = null,
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

/**
 * `POST /auth/sign-in` (and `POST /admin/auth/sign-in`, the alias `webAdmin/` still calls). `token` is the 15-minute
 * access token; `refreshToken` is the 30-day one and is empty on the legacy alias, which issues a long session instead.
 */
@Serializable
data class SignInResponse(
    val token: String,
    val email: String,
    val expiresAt: Long,
    val role: Role = Role.ADMIN,
    val schoolId: String? = null,
    val displayName: String? = null,
    val mustChangePassword: Boolean = false,
    val refreshToken: String? = null,
)

/** A fresh access/refresh pair. `POST /auth/refresh` rotates: the refresh token that was presented is revoked here. */
@Serializable
data class TokenPair(val token: String, val refreshToken: String, val expiresAt: Long)

@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(val token: String, val newPassword: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** `code` is generated (6 characters, A–Z0–9) when it is left out. */
@Serializable
data class CreateSchoolRequest(
    val name: String,
    val code: String? = null,
    val curriculumOptions: List<Curriculum> = emptyList(),
    val gradeOptions: List<Int> = emptyList(),
)

/** Every field is optional: only the ones that are present are written. */
@Serializable
data class UpdateSchoolRequest(
    val name: String? = null,
    val curriculumOptions: List<Curriculum>? = null,
    val gradeOptions: List<Int>? = null,
    val status: SchoolStatus? = null,
)

/** The teacher half of an invite: filled in when `role` is TEACHER, so the account is complete the moment it is accepted. */
@Serializable
data class TeacherProfileInput(
    val displayName: String? = null,
    val photoUrl: String? = null,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val grades: List<Int> = emptyList(),
    val bioEn: String? = null,
    val bioAr: String? = null,
)

@Serializable
data class CreateInviteRequest(val email: String, val role: Role, val teacherProfile: TeacherProfileInput? = null)

/**
 * `POST /admin/schools/{id}/users` (ADMIN only): an account created outright instead of invited by email. It is
 * active immediately, signs in with [password] — at least 10 characters — and must replace it at that first sign-in.
 */
@Serializable
data class CreateUserRequest(
    val email: String,
    val role: Role,
    val password: String,
    val displayName: String? = null,
    val teacherProfile: TeacherProfileInput? = null,
)

/** What the public accept-invite page shows before the person picks a password. */
@Serializable
data class InviteInfo(val email: String, val role: Role, val schoolName: String? = null, val expiresAt: Long = 0)

@Serializable data class AcceptInviteRequest(val password: String, val displayName: String? = null)

/** `GET /me/permissions`: the keys of `permissions.json` the caller's role holds. */
@Serializable
data class MePermissions(val role: Role, val permissions: List<String> = emptyList(), val readOnly: Boolean = false)

/** `GET /admin/users` filters; an absent field does not filter. */
@Serializable
data class UserFilter(val role: Role? = null, val schoolId: String? = null, val status: UserStatus? = null)

/** `PATCH /admin/users/{id}`: status (disable/enable), display name, or role within the same school. */
@Serializable
data class UpdateUserRequest(val status: UserStatus? = null, val displayName: String? = null, val role: Role? = null)

/**
 * The Schools Dashboard's view of the backend (P1.3). The Angular client is generated from `server/openapi.json`
 * and the server is the only implementation, so nothing in this module implements the interface — it is the
 * contract both sides are checked against.
 */
interface DashboardApi {
    // ---- auth (public except change-password)
    suspend fun signIn(email: String, password: String): SignInResponse
    /** Rotation: the old refresh token is revoked and a new pair issued; presenting a revoked one kills the family. */
    suspend fun refresh(request: RefreshRequest): TokenPair
    suspend fun signOut(request: RefreshRequest)
    /** Always succeeds, whether or not the address belongs to an account. */
    suspend fun forgotPassword(request: ForgotPasswordRequest)
    suspend fun resetPassword(request: ResetPasswordRequest)
    /** Clears `mustChangePassword` and revokes every other session of the user. */
    suspend fun changePassword(request: ChangePasswordRequest)

    // ---- the caller
    suspend fun me(): DashboardUser
    suspend fun myPermissions(): MePermissions

    // ---- schools (Admin)
    suspend fun schools(): List<SchoolSummary>
    suspend fun createSchool(request: CreateSchoolRequest): School
    suspend fun school(schoolId: String): School
    suspend fun updateSchool(schoolId: String, request: UpdateSchoolRequest): School

    // ---- dashboard users and invites
    suspend fun users(filter: UserFilter = UserFilter()): List<DashboardUser>
    suspend fun updateUser(userId: String, request: UpdateUserRequest): DashboardUser
    /** Emails the person a reset link; the account itself is untouched. */
    suspend fun resetUserPassword(userId: String)
    suspend fun createInvite(schoolId: String, request: CreateInviteRequest): Invite
    /** ADMIN only: creates the account there and then, with `mustChangePassword` set; 409 when the address is taken. */
    suspend fun createUser(schoolId: String, request: CreateUserRequest): DashboardUser
    /** Public: what the accept-invite page shows. */
    suspend fun inviteInfo(token: String): InviteInfo
    /** Public: sets the password, activates the account and signs the person in. */
    suspend fun acceptInvite(token: String, request: AcceptInviteRequest): SignInResponse

    // ---- View as… (§5): a read-only token for a Teacher or Managerial user, audit-logged on every request
    suspend fun impersonate(userId: String): SignInResponse

    /** Public: what a parent sees after typing a school code in Add child. */
    suspend fun schoolByCode(code: String): JoinSchoolInfo
}
