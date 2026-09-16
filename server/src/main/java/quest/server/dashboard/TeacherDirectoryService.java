package quest.server.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.config.Json;

/**
 * §6 screen 20: "the school's teachers and their classes, read-only". The Managerial view of the staff — who they
 * are, what they teach, which classes are theirs and whether they have published anything lately.
 *
 * <p>Read-only on purpose: §5 gives a Managerial user her school's complaints and usage, not its accounts. Changing
 * a teacher's profile is `PATCH /admin/users/{id}` and the teacher package (P4.0); assigning her a class is the
 * School page's Classes tab. Nothing here writes.
 *
 * <p>Five statements whatever the number of teachers: the accounts, their profiles, the school's classes, how many
 * lessons each published and when each last did.
 */
@Service
public class TeacherDirectoryService {
    private final UserRepository users; private final TeacherRepository profiles; private final SchoolClassService classes;
    private final EntityManager em; private final Json json;

    public TeacherDirectoryService(UserRepository users, TeacherRepository profiles, SchoolClassService classes,
                                   EntityManager em, Json json) {
        this.users = users; this.profiles = profiles; this.classes = classes; this.em = em; this.json = json;
    }

    @Transactional(readOnly = true)
    public List<SchoolDataDto.TeacherSummary> teachers(String schoolId) {
        var accounts = users.findBySchoolIdAndRole(schoolId, "TEACHER").stream()
                .sorted((a, b) -> a.getEmail().compareToIgnoreCase(b.getEmail())).toList();
        if (accounts.isEmpty()) return List.of();
        var ids = accounts.stream().map(UserEntity::getId).toList();

        var byUser = new LinkedHashMap<String, TeacherEntity>();
        profiles.findAllById(ids).forEach(p -> byUser.put(p.getUserId(), p));

        var classesByTeacher = new LinkedHashMap<String, List<SchoolDataDto.SchoolClass>>();
        for (var k : classes.listChecked(schoolId))
            if (k.teacherId() != null) classesByTeacher.computeIfAbsent(k.teacherId(), t -> new ArrayList<>()).add(k);

        var published = new LinkedHashMap<String, Published>();
        var scope = Map.<String, Object>of("schoolId", schoolId);
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT k.teacher_id, COUNT(*) AS n, MAX(l.published_at) AS last FROM lessons l"
                        + " JOIN classes k ON k.id = l.class_id WHERE l.school_id = :schoolId AND l.status = 'published'"
                        + " AND k.teacher_id IS NOT NULL GROUP BY k.teacher_id", scope))) {
            var at = Reports.instant(row[2]);
            published.put(Reports.text(row[0]), new Published((int) Reports.number(row[1]), at == null ? null : at.toEpochMilli()));
        }

        var out = new ArrayList<SchoolDataDto.TeacherSummary>(accounts.size());
        for (var user : accounts) {
            var profile = byUser.get(user.getId());
            var stats = published.getOrDefault(user.getId(), Published.NONE);
            out.add(new SchoolDataDto.TeacherSummary(user.getId(), user.getEmail(), user.getDisplayName(), user.getPhotoUrl(),
                    user.getStatus(),
                    profile == null ? List.of() : json.strings(profile.getSubjectsJson()),
                    profile == null ? null : profile.getCurriculum(),
                    profile == null ? List.<Integer>of() : json.read(profile.getGradesJson(), new TypeReference<List<Integer>>() {}),
                    classesByTeacher.getOrDefault(user.getId(), List.of()),
                    stats.lessons(), stats.lastAt()));
        }
        return List.copyOf(out);
    }

    /** How much one teacher has published, ever; {@link #NONE} is a teacher who has published nothing. */
    private record Published(int lessons, Long lastAt) {
        static final Published NONE = new Published(0, null);
    }
}
