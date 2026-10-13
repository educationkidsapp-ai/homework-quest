package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * MA1 `backend/admin-people` — the owner's admin-role list (2026-09-30) item 4: **a workers page**, "create with full
 * name, job, mobile".
 *
 * A **worker** is a member of the school's staff who does not teach and does not sign in: the caretaker, the driver,
 * the nurse, the secretary. There is deliberately no account behind one — no `users` row, no password, no [Role] — so
 * this file has no `…Created` type carrying a temporary password the way [TeacherCreated] and its coordinator and
 * manager mirrors do. She is one row of `workers` (V25), and she is on the Admin's Home count of "other workers".
 *
 * `job` is free text, because the schools do not share a job list and one that refused "bus supervisor" would be
 * wrong within a week. `phone` is the normalised E.164 form the server stores (MH1), never what was typed.
 *
 * Kept in its own file for the reason [Coordinator] is: one screen's contract, read in one place.
 */

/**
 * One member of the school's non-teaching staff. [createdAt] is epoch milliseconds, as every other row's is.
 *
 * Feature 13 gave her one thing she never had: a login, for the **mobile app** only. [hasAppAccess] says whether an
 * Admin has enabled it, and [username] is what she types in ([WorkerAccess.username]) — absent while she has no
 * account. The password is never on this row: it is answered once, by the route that sets it.
 */
@Serializable
data class Worker(
    val id: String,
    val fullName: String,
    val job: String,
    val phone: String? = null,
    val active: Boolean = true,
    val createdAt: Long = 0,
    val hasAppAccess: Boolean = false,
    val username: String? = null,
)

/**
 * Feature 13, the body of `POST /admin/workers/{id}/app-access` and of `POST /admin/workers/{id}/reset-password`:
 * what the Admin reads out to her, **once**.
 *
 * [username] is generated rather than typed — `w.<first name in ASCII>.<4 digits>` — because a worker has no work
 * address to sign in with, and it is stored in the login column so that `POST /auth/sign-in` takes it as it stands.
 * [temporaryPassword] is `TemporaryPasswords.generate()`'s twelve characters and the account carries
 * `mustChangePassword`, so it stops being a secret the first time she signs in. Neither value is readable
 * afterwards: a worker who has lost her password gets a new one from the reset route.
 */
@Serializable
data class WorkerAccess(val username: String, val temporaryPassword: String)

/** `POST /admin/workers`: the three fields the owner's page asks for, the mobile number optional. */
@Serializable
data class CreateWorkerRequest(val fullName: String, val job: String, val phone: String? = null)

/**
 * `PATCH /admin/workers/{id}`: only the fields that are present are written. `active = false` is the same retirement
 * `DELETE /admin/workers/{id}` performs — there is no hard delete, because a person who has left is part of the
 * school's record.
 */
@Serializable
data class UpdateWorkerRequest(
    val fullName: String? = null,
    val job: String? = null,
    val phone: String? = null,
    val active: Boolean? = null,
)
