package quest.server.staffattendance;

import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;

/**
 * The public face of {@link BoardScope}, for a feature outside this package that has to show a leader <em>exactly
 * the people the attendance board shows her</em> — feature 31's teaching material board is the first.
 *
 * <p>It holds no rule of its own on purpose: every answer is {@link BoardScope}'s, re-shaped into records that are
 * public (its own {@code View} and {@link AttendanceStaff.Employee} are package-private, and the `users` row they
 * carry is nobody else's business). Changing who a leader sees stays a change to {@link BoardScope} alone, so the
 * two boards can never drift apart.
 */
@Component
public class StaffBoardScope {
    /** One employee in view: `USER` keys `users.id`, `WORKER` keys `workers.id`, as everywhere in this package. */
    public record Employee(String kind, String id, String fullName, String role) {}

    /** The employees in view, in the directory's name order, and how wide the view is (`SCHOOL`…`REPORTS`). */
    public record View(String scope, List<Employee> employees) {}

    private final BoardScope scope; private final AttendanceStaff staff;

    StaffBoardScope(BoardScope scope, AttendanceStaff staff) { this.scope = scope; this.staff = staff; }

    public View of(String schoolId, Principals.User caller) {
        var view = scope.of(schoolId, caller);
        return new View(view.scope(), view.employees().stream().map(StaffBoardScope::employee).toList());
    }

    /** One employee in view, or 404 — the same answer as an id that does not exist ({@link BoardScope#one}). */
    public Employee one(String schoolId, Principals.User caller, String kind, String id) {
        return employee(scope.one(schoolId, caller, kind, id));
    }

    /** Every employee of the school, for a job that has no caller and so no scope to narrow by. */
    public List<Employee> all(String schoolId) {
        return staff.all(schoolId).stream().map(StaffBoardScope::employee).toList();
    }

    private static Employee employee(AttendanceStaff.Employee e) {
        return new Employee(e.kind(), e.id(), e.fullName(), e.role());
    }
}
