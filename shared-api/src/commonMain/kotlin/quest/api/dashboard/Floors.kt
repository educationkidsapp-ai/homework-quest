package quest.api.dashboard

import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.Subject

/**
 * Feature 20 `floor supervisor` (docs/features/20-floor-supervisor-role.md).
 *
 * A **floor** (الدور) is a named group of sections inside one academy. The Admin creates one, puts classes on it and
 * assigns one or more **floor supervisors** ([Role.FLOOR_SUPERVISOR]), who look *across* those classes rather than
 * down one subject: today's staff attendance for the teachers on the floor, today's register for its children,
 * read-only child pages, and messages with those parents, those teachers, the managers and the Admin.
 *
 * **A class is on at most one floor** — `POST /admin/floors` and `PATCH /admin/floors/{id}` answer
 * `409 class_on_another_floor` otherwise — so "who supervises 1A" has exactly one answer.
 *
 * Her area is `/floor`, read-only like `/coordinator`; the Admin's half is `/admin/floors` and
 * `/admin/floor-supervisors`. The whole feature is behind the `floorSupervisor` flag: every route is a 404 while a
 * school has it off. Kept in its own file for the reason [Coordinator] is: one role's area, read in one place.
 */

/** One floor as a row names it, without its classes. [academy] is a curriculum. */
@Serializable
data class FloorRef(val id: String, val name: String, val academy: Curriculum)

/** One section on a floor — the Admin's multi-select row and the supervisor's Classes card. */
@Serializable
data class FloorClass(
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val childrenCount: Int = 0,
    val floorId: String? = null,
    val floorName: String? = null,
)

/** One supervisor of a floor, as the Admin's Floors page lists her inside it. */
@Serializable
data class FloorSupervisorRef(val userId: String, val displayName: String)

/** `GET /admin/floors`: a floor with everything on it. */
@Serializable
data class Floor(
    val id: String,
    val name: String,
    val academy: Curriculum,
    val classes: List<FloorClass> = emptyList(),
    val supervisors: List<FloorSupervisorRef> = emptyList(),
)

/** `GET /admin/floor-supervisors`: her account and the floors she holds. */
@Serializable
data class FloorSupervisorAccount(
    val userId: String,
    val email: String,
    val fullName: String,
    val phone: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
    val floors: List<FloorRef> = emptyList(),
)

/** 201 from `POST /admin/floor-supervisors`: the one and only sight of the password. */
@Serializable
data class FloorSupervisorCreated(val supervisor: FloorSupervisorAccount, val temporaryPassword: String)

/**
 * `GET /floor/me`: her floors and how big they are — the three numbers her Home leads with, so the landing screen
 * costs one request. [classes] counts the sections on her floors, [teachers] the people who teach in them, and
 * [children] the pupils sitting in them.
 */
@Serializable
data class FloorMe(
    val userId: String,
    val email: String,
    val displayName: String,
    val floors: List<FloorRef> = emptyList(),
    val classes: Int = 0,
    val teachers: Int = 0,
    val children: Int = 0,
)

/** One class's register for today: the roster's five numbers, which is all her Home draws per class. */
@Serializable
data class FloorClassAttendance(
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val floorId: String? = null,
    val floorName: String? = null,
    val children: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val late: Int = 0,
    val excused: Int = 0,
    val notMarked: Int = 0,
)

/**
 * `GET /floor/home`: today in the school's own time zone ([day], `yyyy-MM-dd`).
 *
 * [board] is feature 17's own staff board, narrowed to the teachers on her floors plus anyone whose direct manager
 * she is; it is **null** when the school has `staffAttendance` off, rather than taking the rest of the screen down
 * with it. [classes] is the children's register of every class on her floors.
 */
@Serializable
data class FloorHome(
    val day: String,
    val board: AttendanceBoard? = null,
    val classes: List<FloorClassAttendance> = emptyList(),
)

/** `GET /floor/teachers`: every teacher holding an assignment on one of her floors. Read-only. */
@Serializable
data class FloorTeacher(
    val userId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val phone: String? = null,
    val subjects: List<Subject> = emptyList(),
    val sections: List<FloorClass> = emptyList(),
)

/** `GET /floor/children?q=`: a child on her floors. Her page itself is §7's `ChildReport`. */
@Serializable
data class FloorChild(
    val id: String,
    val name: String,
    val avatarColor: String? = null,
    val classId: String? = null,
    val className: String? = null,
    val floorId: String? = null,
    val floorName: String? = null,
)

/** One colleague she may open a thread with, for the Messages chooser. [role] is `MANAGERIAL` or `ADMIN`. */
@Serializable
data class FloorPerson(val userId: String, val displayName: String, val email: String? = null, val role: String)

/**
 * `GET /floor/chat/peers`: whom `POST /floor/chat/threads` accepts besides a parent of her floor (by `childId`) and
 * a teacher on it (`GET /floor/teachers`).
 */
@Serializable
data class FloorChatPeers(
    val managers: List<FloorPerson> = emptyList(),
    val admins: List<FloorPerson> = emptyList(),
)
