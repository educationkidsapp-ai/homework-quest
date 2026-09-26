package quest.server.coordinator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.platform.SafeText;
import quest.server.teacher.AnnouncementRepository;
import quest.server.teacher.Entities.AnnouncementEntity;
import quest.server.teacher.TeacherDto;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TenantContext;

/**
 * R4 (DR4): "announcements from a coordinator go to every parent of every class in scope <em>through the existing
 * announcements feature</em>". So this writes `announcements` rows and nothing else — one per class, with the
 * coordinator's own user id in `teacher_id` — and the parent's `GET /children/{id}/announcements` picks them up with
 * no change at all, in the app screen phase 2 shipped.
 *
 * <p><strong>The audience is her scope.</strong> An empty `classIds` means every section {@link CoordinatorScope}
 * gives her; a named list is checked one section at a time through {@link CoordinatorScope#requireSection}, so a
 * section of the other track or of a subject she does not coordinate is 403 and another school's is 404. Nothing
 * reads a school, a curriculum or a grade from the request.
 *
 * <p><strong>No parent notification row.</strong> Notifications (E2) are a dashboard user's bell — the `/me/…` routes
 * and the `user:<id>` socket — and a parent has neither, so a coordinator's note reaches her through the
 * announcements screen she already has. Giving parents a bell of their own is its own package.
 */
@Service
public class CoordinatorAnnouncementService {
    /** A note, not an essay, and never scheduled to outlive a school year — `AnnouncementService`'s two numbers. */
    private static final int MAX_BODY = 1000, MAX_LIFETIME_DAYS = 400;

    private final AnnouncementRepository announcements; private final CoordinatorScope scope;
    private final UserRepository users; private final TenantContext tenant;

    public CoordinatorAnnouncementService(AnnouncementRepository announcements, CoordinatorScope scope,
                                          UserRepository users, TenantContext tenant) {
        this.announcements = announcements; this.scope = scope; this.users = users; this.tenant = tenant;
    }

    /** `GET /coordinator/announcements`: the ones she wrote, newest first, with each class's own name on the row. */
    public List<TeacherDto.Announcement> mine(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var rows = announcements.findBySchoolIdAndTeacherIdOrderByPublishedAtDesc(schoolId, caller.userId());
        if (rows.isEmpty()) return List.of();
        return dtos(rows, sections(caller), caller);
    }

    @Transactional
    public List<TeacherDto.Announcement> create(Principals.User caller, CoordinatorDto.CreateAnnouncementRequest request) {
        var byId = sections(caller);
        var targets = new ArrayList<ClassEntity>();
        if (request.classIds() == null || request.classIds().isEmpty()) targets.addAll(byId.values());
        else for (String classId : request.classIds().stream().distinct().toList()) targets.add(scope.requireSection(caller, classId));
        if (targets.isEmpty()) throw ApiException.badRequest("You coordinate no class yet, so there is nobody to tell.");

        String bodyEn = SafeText.plainText(request.bodyEn(), "bodyEn", MAX_BODY);
        if (bodyEn == null) throw ApiException.badRequest("bodyEn must not be empty");
        String bodyAr = SafeText.plainText(request.bodyAr(), "bodyAr", MAX_BODY);
        var now = Instant.now();
        var expiresAt = expiry(request.expiresAt(), now);

        var written = new ArrayList<AnnouncementEntity>(targets.size());
        for (var target : targets) {
            var row = new AnnouncementEntity();
            row.setId(UUID.randomUUID().toString());
            row.setSchoolId(target.getSchoolId()); row.setTeacherId(caller.userId()); row.setClassId(target.getId());
            row.setBodyEn(bodyEn); row.setBodyAr(bodyAr);
            row.setPublishedAt(now); row.setExpiresAt(expiresAt); row.setCreatedAt(now);
            written.add(row);
        }
        announcements.saveAll(written);
        for (var target : targets) byId.putIfAbsent(target.getId(), target);
        return dtos(written, byId, caller);
    }

    private LinkedHashMap<String, ClassEntity> sections(Principals.User caller) {
        var byId = new LinkedHashMap<String, ClassEntity>();
        for (var section : scope.sectionsOf(caller)) byId.put(section.getId(), section);
        return byId;
    }

    private List<TeacherDto.Announcement> dtos(List<AnnouncementEntity> rows, LinkedHashMap<String, ClassEntity> byId, Principals.User caller) {
        String author = users.findById(caller.userId()).map(quest.server.auth.Entities.UserEntity::getDisplayName).orElse(null);
        var out = new ArrayList<TeacherDto.Announcement>(rows.size());
        for (var row : rows) {
            var klass = byId.get(row.getClassId());
            out.add(new TeacherDto.Announcement(row.getId(), row.getSchoolId(), row.getTeacherId(), author, row.getClassId(),
                    klass == null ? null : klass.getCurriculum(), klass == null ? null : klass.getGrade(),
                    klass == null ? null : klass.getSubject(), row.getBodyEn(), row.getBodyAr(),
                    row.getPublishedAt().toEpochMilli(), row.getExpiresAt() == null ? null : row.getExpiresAt().toEpochMilli(),
                    row.getCreatedAt().toEpochMilli()));
        }
        return List.copyOf(out);
    }

    private static Instant expiry(Long millis, Instant now) {
        if (millis == null) return null;
        var at = Instant.ofEpochMilli(millis);
        if (!at.isAfter(now)) throw ApiException.badRequest("expiresAt must be in the future");
        if (at.isAfter(now.plus(Duration.ofDays(MAX_LIFETIME_DAYS))))
            throw ApiException.badRequest("expiresAt must be within " + MAX_LIFETIME_DAYS + " days");
        return at;
    }
}
