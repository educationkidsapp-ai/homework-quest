package quest.server.staffattendance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.workers.WorkerRepository;

/**
 * Who counts as an employee for feature 14, in one place: the school's teachers, coordinators and managers
 * (`users`, kind `USER`) and its workers (`workers`, kind `WORKER`), active ones only. A worker with app access
 * (feature 13) also has a `users` row with role WORKER; the `workers` row is the one that counts, so that row is
 * skipped here and nobody appears twice.
 *
 * <p>Every lookup compares the school explicitly: `findById` is `em.find`, which the `school` filter never touches.
 */
@Component
class AttendanceStaff {
    /**
     * The roles that are staff of a school and may be a direct manager. ADMIN is the platform's, not a school's.
     * Feature 20 adds FLOOR_SUPERVISOR: she has attendance of her own and may be named a direct manager, which is
     * what makes feature 16's lateness alerts reach her.
     */
    static final Set<String> STAFF_ROLES = Set.of("TEACHER", "COORDINATOR", "MANAGERIAL", quest.server.tenancy.FloorScope.ROLE);

    record Employee(String kind, String id, String fullName, String role, UserEntity user) {}

    private final UserRepository users; private final WorkerRepository workers;

    AttendanceStaff(UserRepository users, WorkerRepository workers) { this.users = users; this.workers = workers; }

    List<Employee> all(String schoolId) {
        var out = new ArrayList<Employee>();
        for (var u : users.findBySchoolId(schoolId))
            if (STAFF_ROLES.contains(u.getRole()) && active(u)) out.add(employee(u));
        for (var w : workers.findBySchoolIdOrderByFullNameAsc(schoolId))
            if (w.isActive()) out.add(new Employee(StaffAttendanceDto.KIND_WORKER, w.getId(), w.getFullName(), "WORKER", null));
        out.sort(Comparator.comparing((Employee e) -> e.fullName().toLowerCase(Locale.ROOT)).thenComparing(Employee::id));
        return out;
    }

    Optional<Employee> find(String schoolId, String kind, String id) {
        if (id == null) return Optional.empty();
        if (StaffAttendanceDto.KIND_USER.equals(kind)) return user(schoolId, id).map(AttendanceStaff::employee);
        if (StaffAttendanceDto.KIND_WORKER.equals(kind))
            return workers.findById(id).filter(w -> Objects.equals(schoolId, w.getSchoolId()) && w.isActive())
                    .map(w -> new Employee(StaffAttendanceDto.KIND_WORKER, w.getId(), w.getFullName(), "WORKER", null));
        return Optional.empty();
    }

    /**
     * Feature 15: who the caller of `/staff/attendance/**` is, as an employee. A WORKER principal carries the
     * `users` row feature 13 minted for her, so she is resolved to her `workers` row; a teacher, coordinator or
     * manager <em>is</em> her `users` row. A platform ADMIN is nobody's employee and comes back empty, which is how
     * the routes refuse her: she runs the school from the dashboard and has no attendance of her own.
     */
    Optional<Employee> forPrincipal(String schoolId, quest.server.auth.Principals.User caller) {
        if (caller == null || schoolId == null) return Optional.empty();
        if ("WORKER".equals(caller.role()))
            return workers.findOneBySchoolIdAndUserId(schoolId, caller.userId()).filter(w -> w.isActive())
                    .map(w -> new Employee(StaffAttendanceDto.KIND_WORKER, w.getId(), w.getFullName(), "WORKER", null));
        return user(schoolId, caller.userId()).map(AttendanceStaff::employee);
    }

    /** An active teacher, coordinator or manager of this school: the people who may be a direct manager. */
    Optional<UserEntity> user(String schoolId, String id) {
        if (id == null) return Optional.empty();
        return users.findById(id).filter(u -> Objects.equals(schoolId, u.getSchoolId()) && STAFF_ROLES.contains(u.getRole()) && active(u));
    }

    /** The `users` id her bell and phone hang off: herself, or a worker's account. Null when she has none. */
    String account(Employee employee) {
        if (StaffAttendanceDto.KIND_USER.equals(employee.kind())) return employee.id();
        return workers.findById(employee.id()).map(w -> w.getUserId()).orElse(null);
    }

    static String name(UserEntity u) {
        return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName();
    }

    private static Employee employee(UserEntity u) {
        return new Employee(StaffAttendanceDto.KIND_USER, u.getId(), name(u), u.getRole(), u);
    }

    private static boolean active(UserEntity u) { return !"disabled".equals(u.getStatus()); }
}
