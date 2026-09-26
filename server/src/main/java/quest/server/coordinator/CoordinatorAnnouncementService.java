package quest.server.coordinator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
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
 * <p><strong>RM2 (DR6) put the broadcast in front of it.</strong> `POST /coordinator/announcements` and
 * `POST /coordinator/broadcasts` are two doors onto {@link quest.server.broadcasts.BroadcastService}, which resolves
 * her scope, validates the body and writes the `broadcasts` row; {@link #mirror} is what it calls to keep the rows this
 * class always wrote. So there is one feature with one set of rules, and the app screen that already exists keeps
 * working while RM4 moves the app to `GET /children/{id}/broadcasts`.
 *
 * <p><strong>No parent notification row.</strong> Notifications (E2) are a dashboard user's bell — the `/me/…` routes
 * and the `user:<id>` socket — and a parent has neither, so a coordinator's note reaches her through the
 * announcements screen she already has. Giving parents a bell of their own is its own package.
 */
@Service
public class CoordinatorAnnouncementService {
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

    /**
     * RM2 (DR6): the `announcements` rows behind a coordinator's broadcast. {@link quest.server.broadcasts.BroadcastService}
     * is the single writer now — `POST /coordinator/announcements` and `POST /coordinator/broadcasts` are two doors onto
     * it — and this keeps writing the rows the app screen phase 2 shipped reads, so a coordinator's note reaches a
     * parent through `GET /children/{id}/announcements` exactly as it did before the broadcast row existed. The targets,
     * the body and the expiry are already resolved and validated by the caller, whose scope decided them.
     */
    @Transactional
    public List<TeacherDto.Announcement> mirror(Principals.User caller, List<ClassEntity> targets, String bodyEn,
                                                String bodyAr, Instant expiresAt) {
        var now = Instant.now();
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
        var byId = sections(caller);
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
}
