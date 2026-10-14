package quest.server.staffattendance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;

/**
 * Feature 17: whose attendance a leader sees on the board and in the report, in one place.
 *
 * <ul>
 *   <li><strong>The Admin</strong>: every employee of the school.</li>
 *   <li><strong>A manager</strong>: the people of the academy she directs ({@link DirectManagerResolver#academyDirector})
 *       and the people whose direct manager she is.</li>
 *   <li><strong>A teacher or a coordinator</strong>: only the people whose direct manager she is (feature 14's
 *       profile). With nobody, the board is empty — she holds the permission because any of them may be named.</li>
 * </ul>
 * Nobody sees herself unless she is the Admin, who has no attendance of her own anyway.
 */
@Component
class BoardScope {
    /** The employees in view, in the directory's name order, and how wide the view is. */
    record View(String scope, List<AttendanceStaff.Employee> employees) {}

    private final AttendanceStaff staff; private final StaffAttendanceProfileRepository profiles;
    private final DirectManagerResolver managers;

    BoardScope(AttendanceStaff staff, StaffAttendanceProfileRepository profiles, DirectManagerResolver managers) {
        this.staff = staff; this.profiles = profiles; this.managers = managers;
    }

    View of(String schoolId, Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        var everyone = staff.all(schoolId);
        if ("ADMIN".equals(caller.role())) return new View(StaffAttendanceDto.SCOPE_SCHOOL, everyone);

        // Her direct reports: a profile naming her, and she is still someone who may be a direct manager.
        Set<String> reports = new HashSet<>();
        if (staff.user(schoolId, caller.userId()).isPresent())
            for (var p : profiles.findBySchoolId(schoolId))
                if (caller.userId().equals(p.getDirectManagerId())) reports.add(key(p.getStaffKind(), p.getStaffId()));
        boolean manager = "MANAGERIAL".equals(caller.role());

        var visible = new ArrayList<AttendanceStaff.Employee>();
        for (var e : everyone) {
            if (StaffAttendanceDto.KIND_USER.equals(e.kind()) && e.id().equals(caller.userId())) continue;
            if (reports.contains(key(e.kind(), e.id()))
                    || manager && caller.userId().equals(managers.academyDirector(schoolId, e.kind(), e.id())))
                visible.add(e);
        }
        return new View(manager ? StaffAttendanceDto.SCOPE_ACADEMY : StaffAttendanceDto.SCOPE_REPORTS, visible);
    }

    /** One employee in view, or 404 — the same answer as an id that does not exist, so nothing leaks about who does. */
    AttendanceStaff.Employee one(String schoolId, Principals.User caller, String kind, String id) {
        return of(schoolId, caller).employees().stream()
                .filter(e -> e.kind().equals(kind) && e.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Employee"));
    }

    static String key(String kind, String id) { return kind + ":" + id; }
}
