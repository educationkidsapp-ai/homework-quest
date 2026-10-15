package quest.server.floors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.Floors.kt` (feature 20).
 *
 * <p>Records rather than the Kotlin types, for the reason {@link quest.server.coordinator.CoordinatorDto} is:
 * springdoc derives a real schema from a record and `server/openapi.json` is what the Angular client is generated
 * from, so a route answering the kotlinx encoding as a `String` would be documented as `type: string` and generate
 * nothing usable. `academy`, `curriculum` and `status` are the lower-cased wire strings the enums serialise to,
 * exactly as `CoordinatorDto` and `ClassDto` carry them.
 *
 * <p>The child page is the one read with no shape of its own: `GET /floor/children/{id}` answers §7's
 * {@link quest.server.grading.GradingDto.ChildReport}, the body a coordinator and a manager already read.
 */
public final class FloorDto {
    private FloorDto() {}

    /** One floor as a row names it, without its classes — what a `FloorMe` and a supervisor account carry. */
    public record FloorRef(String id, String name, String academy) {}

    /** One section on a floor: the Admin's multi-select row and the supervisor's Classes card. */
    public record FloorClass(String classId, String className, String curriculum, int grade, int childrenCount,
                             String floorId, String floorName) {}

    /** One supervisor of a floor, as the Admin's Floors page lists her inside the floor. */
    public record FloorSupervisorRef(String userId, String displayName) {}

    /** `GET /admin/floors`: a floor with everything on it, in two statements for the whole page. */
    public record Floor(String id, String name, String academy, List<FloorClass> classes,
                        List<FloorSupervisorRef> supervisors) {}

    /**
     * `POST /admin/floors`. `classIds` and `supervisorIds` may be absent — a floor with no class yet is a legitimate
     * half-built row the Admin finishes later — and a class already on another floor is a 409, because a class is on
     * at most one floor (V42's unique index).
     */
    public record CreateFloorRequest(@NotBlank @Size(max = 80) String name, @NotBlank String academy,
                                     List<String> classIds, List<String> supervisorIds) {}

    /**
     * `PATCH /admin/floors/{id}`: only the fields that are present are written, and a present `classIds` or
     * `supervisorIds` is the <em>complete</em> set the floor should hold afterwards — the contract
     * `PUT /admin/teachers/{id}/assignments` has, which is what makes the screen's Save one request.
     */
    public record UpdateFloorRequest(@Size(max = 80) String name, String academy,
                                     List<String> classIds, List<String> supervisorIds) {}

    // ---------------------------------------------------------------- her accounts (`/admin/floor-supervisors`)

    public record FloorSupervisorAccount(String userId, String email, String fullName, String phone, String status,
                                         List<FloorRef> floors) {}

    /** `POST /admin/floor-supervisors`: her account. `floorIds` may be empty — a floor is assigned on the Floors page too. */
    public record CreateFloorSupervisorRequest(@NotBlank @Size(max = 80) String fullName, @NotBlank String email,
                                               @Size(max = quest.server.auth.DashboardDto.PHONE) String phone,
                                               List<String> floorIds) {}

    /** 201, with the one and only sight of the password — the contract `TeacherCreated` and `CoordinatorCreated` have. */
    public record FloorSupervisorCreated(FloorSupervisorAccount supervisor, String temporaryPassword) {}

    /**
     * `PATCH /admin/floor-supervisors/{id}`: only the fields that are present are written, and a present `floorIds`
     * is the complete set of floors she should hold afterwards.
     */
    public record UpdateFloorSupervisorRequest(@Size(max = 80) String fullName,
                                               @Size(max = quest.server.auth.DashboardDto.PHONE) String phone,
                                               Boolean active, List<String> floorIds) {}

    // ---------------------------------------------------------------- her area (`/floor`)

    /** `GET /floor/me`: her floors, and how big they are — the three numbers her Home leads with. */
    public record FloorMe(String userId, String email, String displayName, List<FloorRef> floors,
                          int classes, int teachers, int children) {}

    /** One class's register for today: the roster's five numbers, which is all her Home draws per class. */
    public record FloorClassAttendance(String classId, String className, String curriculum, int grade,
                                       String floorId, String floorName, int children, int present, int absent,
                                       int late, int excused, int notMarked) {}

    /**
     * `GET /floor/home`: today in the school's own zone — the staff attendance board for the people she leads
     * (null when the school has `staffAttendance` off) and the children's register of every class on her floors.
     */
    public record FloorHome(String day, quest.server.staffattendance.StaffAttendanceDto.AttendanceBoard board,
                            List<FloorClassAttendance> classes) {}

    /** `GET /floor/teachers`: every teacher holding an assignment on one of her floors, with the sections and subjects. */
    public record FloorTeacher(String userId, String email, String displayName, String photoUrl, String phone,
                               List<String> subjects, List<FloorClass> sections) {}

    /** `GET /floor/children?q=`: a child on her floors, read-only; `GET /floor/children/{id}` is her page. */
    public record FloorChild(String id, String name, String avatarColor, String classId, String className,
                             String floorId, String floorName) {}

    /** One colleague she may open a thread with, for the Messages chooser. */
    public record FloorPerson(String userId, String displayName, String email, String role) {}

    /**
     * `GET /floor/chat/peers`: whom `POST /floor/chat/threads` will accept besides a parent of her floor (by
     * `childId`) and a teacher on it (`GET /floor/teachers`) — the school's managers and the platform Admins.
     */
    public record FloorChatPeers(List<FloorPerson> managers, List<FloorPerson> admins) {}

    /**
     * `POST /floor/chat/threads`: exactly one of the four — the parent of a child on her floor, a teacher on it, a
     * manager of the school, or the Admin. Two or none is a 400, because each names a different conversation.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "OpenFloorThreadRequest")
    public record FloorThreadRequest(String childId, String teacherUserId, String managerUserId, String adminUserId) {}
}
