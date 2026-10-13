package quest.server.staffattendance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.tenancy.StaffScopeRepository;

/**
 * Who hears about an employee first (feature 14): her direct manager; when none is set, the director of her academy;
 * when there is none, the Admin. Feature 16 sends the immediate alert here and feature 18 the approvals.
 *
 * <p><strong>Her academy</strong> is a curriculum (`british`, `american`): a teacher's is `teachers.curriculum`, a
 * coordinator's the curricula of her `staff_scopes` rows. A manager is herself a director and a worker belongs to no
 * academy, so both fall through to the Admin. <strong>The director</strong> of an academy is an active manager whose
 * department row (`subject` NULL) names it. When an academy has two, the earliest created wins, and when the employee
 * spans two academies they are tried in name order, so the answer is the same every time it is asked.
 */
@Service
public class DirectManagerResolver {
    public enum Source { DIRECT, DIRECTOR, ADMIN }

    /** {@code userId} is null when {@code source} is ADMIN: the school's Admins, whoever they are on the day. */
    public record Resolved(Source source, String userId) {}

    private final StaffAttendanceProfileRepository profiles; private final AttendanceStaff directory;
    private final UserRepository users; private final TeacherRepository teachers; private final StaffScopeRepository scopes;

    public DirectManagerResolver(StaffAttendanceProfileRepository profiles, AttendanceStaff directory, UserRepository users,
                                 TeacherRepository teachers, StaffScopeRepository scopes) {
        this.profiles = profiles; this.directory = directory; this.users = users; this.teachers = teachers; this.scopes = scopes;
    }

    public Resolved resolve(String schoolId, String kind, String staffId) {
        var profile = profiles.findOneBySchoolIdAndStaffKindAndStaffId(schoolId, kind, staffId).orElse(null);
        if (profile != null && profile.getDirectManagerId() != null
                && directory.user(schoolId, profile.getDirectManagerId()).isPresent())
            return new Resolved(Source.DIRECT, profile.getDirectManagerId());
        if (StaffAttendanceDto.KIND_USER.equals(kind)) {
            var self = directory.user(schoolId, staffId).orElse(null);
            if (self != null) {
                var director = director(schoolId, self);
                if (director != null) return new Resolved(Source.DIRECTOR, director);
            }
        }
        return new Resolved(Source.ADMIN, null);
    }

    private String director(String schoolId, UserEntity employee) {
        var academies = new TreeSet<String>();
        if ("TEACHER".equals(employee.getRole()))
            teachers.findById(employee.getId()).map(t -> t.getCurriculum()).filter(c -> c != null && !c.isBlank())
                    .ifPresent(c -> academies.add(c.trim().toLowerCase(Locale.ROOT)));
        else if ("COORDINATOR".equals(employee.getRole()))
            for (var row : scopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(schoolId, employee.getId()))
                if (row.getCurriculum() != null && !row.getCurriculum().isBlank()) academies.add(row.getCurriculum().trim().toLowerCase(Locale.ROOT));
        if (academies.isEmpty()) return null;

        var managers = new ArrayList<UserEntity>();
        for (var m : users.findBySchoolIdAndRole(schoolId, "MANAGERIAL"))
            if (!m.getId().equals(employee.getId()) && directory.user(schoolId, m.getId()).isPresent()) managers.add(m);
        if (managers.isEmpty()) return null;
        managers.sort(Comparator.comparing(UserEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(UserEntity::getId));
        var rows = scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, managers.stream().map(UserEntity::getId).toList());
        for (String academy : academies)
            for (var m : managers)
                if (rows.stream().anyMatch(r -> r.getUserId().equals(m.getId()) && r.getSubject() == null
                        && r.getCurriculum() != null && academy.equals(r.getCurriculum().trim().toLowerCase(Locale.ROOT))))
                    return m.getId();
        return null;
    }
}
