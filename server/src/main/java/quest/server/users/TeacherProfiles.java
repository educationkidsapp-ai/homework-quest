package quest.server.users;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.auth.Entities;
import quest.server.auth.TeacherRepository;
import quest.server.config.Json;

/**
 * The teacher half of an account, written the moment the account is made — by an invite ({@link InviteService}) or by
 * the Admin creating the user outright ({@link UserService#create}) — so a TEACHER row is never a user without
 * subjects, curriculum and grades for {@link quest.server.tenancy.TenantGuard} to check a new lesson against.
 */
@Component
public class TeacherProfiles {
    private final TeacherRepository teachers; private final Json json;
    public TeacherProfiles(TeacherRepository teachers, Json json) { this.teachers = teachers; this.json = json; }

    /** Creates the row when it is missing; a null input leaves an existing profile untouched. */
    public void save(String userId, UserDto.TeacherProfileInput profile) {
        var row = teachers.findById(userId).orElseGet(() -> { var t = new Entities.TeacherEntity(); t.setUserId(userId); return t; });
        if (profile != null) {
            row.setSubjectsJson(json.write(profile.subjects() == null ? List.of() : profile.subjects()));
            row.setGradesJson(json.write(profile.grades() == null ? List.of() : profile.grades()));
            row.setCurriculum(profile.curriculum());
            row.setBioEn(profile.bioEn()); row.setBioAr(profile.bioAr());
        }
        row.setUpdatedAt(Instant.now());
        teachers.save(row);
    }
}
