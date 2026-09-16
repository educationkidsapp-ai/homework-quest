package quest.server.teacher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dashboard.ParentAnnouncement;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.platform.SafeText;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TenantContext;

/**
 * §6 screen 16: "a short note to all parents of a class ('Tomorrow we start subtraction'), shown in the app's parent
 * mode". Bilingual like every other parent-facing string — English is required, Arabic optional.
 *
 * <p><strong>Who sees it.</strong> A note names one class, and a class is (school, curriculum, grade, subject); the
 * app asks for one child's notes and gets the live ones of the classes that child sits in — her school, her
 * curriculum, her grade, any subject. {@link #forChild} starts from the child's own `school_id`, so a note never
 * crosses a school even though a parent carries no tenant scope of her own.
 *
 * <p><strong>Live</strong> means published and not yet expired. Nothing is deleted on expiry: the row stays for the
 * teacher's list, and the app simply stops being told about it.
 */
@Service
public class AnnouncementService {
    /** A note, not an essay — it is a card in the app. */
    private static final int MAX_BODY = 1000;
    /** A note scheduled to outlive a school year is a mistake, not a plan. */
    private static final int MAX_LIFETIME_DAYS = 400;

    private final AnnouncementRepository announcements; private final ClassRepository classes;
    private final UserRepository users; private final TeacherAccess access; private final TenantContext tenant;

    public AnnouncementService(AnnouncementRepository announcements, ClassRepository classes, UserRepository users,
                               TeacherAccess access, TenantContext tenant) {
        this.announcements = announcements; this.classes = classes; this.users = users;
        this.access = access; this.tenant = tenant;
    }

    // ---------------------------------------------------------------- the teacher's side

    /**
     * `GET /teacher/announcements`: hers newest first, or the whole school's for a MANAGERIAL or ADMIN caller.
     * Three statements whatever the number of notes — the rows, the school's classes, the authors.
     */
    public List<TeacherDto.Announcement> list(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var rows = access.isTeacher(caller)
                ? announcements.findBySchoolIdAndTeacherIdOrderByPublishedAtDesc(schoolId, caller.userId())
                : announcements.findBySchoolIdOrderByPublishedAtDesc(schoolId);
        if (rows.isEmpty()) return List.of();

        var classesById = new LinkedHashMap<String, ClassEntity>();
        for (var klass : classes.findBySchoolId(schoolId)) classesById.put(klass.getId(), klass);
        var authors = authorsById(rows.stream().map(Entities.AnnouncementEntity::getTeacherId).distinct().toList());

        var out = new ArrayList<TeacherDto.Announcement>(rows.size());
        for (var row : rows) out.add(dto(row, classesById.get(row.getClassId()), authors.get(row.getTeacherId())));
        return List.copyOf(out);
    }

    @Transactional
    public TeacherDto.Announcement create(Principals.User caller, TeacherDto.CreateAnnouncementRequest request) {
        var klass = access.ownedClass(caller, request.classId());
        String bodyEn = SafeText.plainText(request.bodyEn(), "bodyEn", MAX_BODY);
        if (bodyEn == null) throw ApiException.badRequest("bodyEn must not be empty");
        var now = Instant.now();
        var expiresAt = expiry(request.expiresAt(), now);

        var row = new Entities.AnnouncementEntity();
        row.setId(UUID.randomUUID().toString());
        row.setSchoolId(klass.getSchoolId());
        row.setTeacherId(access.authorOf(caller, klass));
        row.setClassId(klass.getId());
        row.setBodyEn(bodyEn);
        row.setBodyAr(SafeText.plainText(request.bodyAr(), "bodyAr", MAX_BODY));
        row.setPublishedAt(now); row.setExpiresAt(expiresAt); row.setCreatedAt(now);
        announcements.save(row);
        return dto(row, klass, users.findById(row.getTeacherId()).orElse(null));
    }

    /**
     * `DELETE /teacher/announcements/{id}`: a real delete, not an expiry. A note is a message, and a teacher who
     * posted the wrong one should be able to take it back rather than leave it visible until a date passes.
     */
    @Transactional
    public void delete(Principals.User caller, String id) {
        var row = announcements.findOneById(id).orElseThrow(() -> ApiException.notFound("announcement"));
        if (access.isTeacher(caller) && !caller.userId().equals(row.getTeacherId())) throw ApiException.notFound("announcement");
        if ("MANAGERIAL".equals(caller.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's announcements but not remove them.");
        announcements.delete(row);
    }

    // ---------------------------------------------------------------- the child's side

    /**
     * `GET /children/{id}/announcements`: the live notes of the classes this child sits in, newest first.
     *
     * <p>Three statements: her classes, the live notes of them, and the teachers who wrote them. The school is the
     * child's own — a parent has no tenant scope, so naming it explicitly is the isolation.
     */
    public List<ParentAnnouncement> forChild(quest.server.children.Entities.ChildEntity child) {
        var hers = classes.findBySchoolIdAndCurriculumAndGrade(child.getSchoolId(), child.getCurriculum(), child.getGrade())
                .stream().map(ClassEntity::getId).toList();
        if (hers.isEmpty()) return List.of();
        var rows = announcements.findLive(child.getSchoolId(), hers, Instant.now());
        if (rows.isEmpty()) return List.of();
        var authors = authorsById(rows.stream().map(Entities.AnnouncementEntity::getTeacherId).distinct().toList());

        var out = new ArrayList<ParentAnnouncement>(rows.size());
        for (var row : rows) {
            var author = authors.get(row.getTeacherId());
            out.add(new ParentAnnouncement(row.getId(),
                    TeacherQuestionService.displayName(author == null ? null : author.getDisplayName()),
                    author == null ? null : author.getPhotoUrl(),
                    row.getBodyEn(), row.getBodyAr(), row.getPublishedAt().toEpochMilli(),
                    row.getExpiresAt() == null ? null : row.getExpiresAt().toEpochMilli()));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, UserEntity> authorsById(List<String> ids) {
        var out = new HashMap<String, UserEntity>();
        if (ids.isEmpty()) return out;
        for (var user : users.findAllById(ids)) out.put(user.getId(), user);
        return out;
    }

    private static Instant expiry(Long millis, Instant now) {
        if (millis == null) return null;
        var at = Instant.ofEpochMilli(millis);
        if (!at.isAfter(now)) throw ApiException.badRequest("expiresAt must be in the future");
        if (at.isAfter(now.plus(java.time.Duration.ofDays(MAX_LIFETIME_DAYS))))
            throw ApiException.badRequest("expiresAt must be within " + MAX_LIFETIME_DAYS + " days");
        return at;
    }

    private static TeacherDto.Announcement dto(Entities.AnnouncementEntity row, ClassEntity klass, UserEntity author) {
        return new TeacherDto.Announcement(row.getId(), row.getSchoolId(), row.getTeacherId(),
                author == null ? null : author.getDisplayName(), row.getClassId(),
                klass == null ? null : klass.getCurriculum(), klass == null ? null : klass.getGrade(),
                klass == null ? null : klass.getSubject(), row.getBodyEn(), row.getBodyAr(),
                row.getPublishedAt().toEpochMilli(),
                row.getExpiresAt() == null ? null : row.getExpiresAt().toEpochMilli(),
                row.getCreatedAt().toEpochMilli());
    }
}
