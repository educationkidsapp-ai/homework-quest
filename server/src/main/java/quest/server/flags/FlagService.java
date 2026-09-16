package quest.server.flags;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;

/** The Admin side of §4: the matrix, the two ways to flip a flag, and the trail every flip leaves. */
@Service
public class FlagService {
    /** `GET /admin/flags/audit?limit=`; the dashboard shows a page at a time. */
    public static final int DEFAULT_AUDIT_LIMIT = 50;
    public static final int MAX_AUDIT_LIMIT = 200;

    private final FeatureFlags flags; private final SchoolFlagRepository overrides; private final FlagAuditRepository audit;
    private final SchoolRepository schools; private final UserRepository users; private final EntityManager em;

    public FlagService(FeatureFlags flags, SchoolFlagRepository overrides, FlagAuditRepository audit,
                       SchoolRepository schools, UserRepository users, EntityManager em) {
        this.flags = flags; this.overrides = overrides; this.audit = audit; this.schools = schools; this.users = users; this.em = em;
    }

    /**
     * The definitions plus one row per school. An ADMIN sees every school; a MANAGERIAL user holds `flag.read` for
     * her own school only, so she gets the one row — scoped here by her token's `schoolId`, never by a parameter.
     *
     * <p>Two queries whatever the number of schools: the definitions, then every override at once (one school's when
     * that is all the caller may see). Asking {@link FeatureFlags#effective} per row would be a query per school.
     */
    public FlagDto.FlagMatrix matrix(Principals.User caller) {
        var flagRows = flags.definitions();
        var definitions = flagRows.stream()
                .map(f -> new FlagDto.FeatureFlagDefinition(f.getKey(), f.getDescription(), f.isDefaultOn(),
                        FlagDto.RolloutStage.from(f.getRolloutStage()), f.getCreatedAt() == null ? 0 : f.getCreatedAt().toEpochMilli()))
                .toList();
        var defaults = new LinkedHashMap<String, Boolean>();
        flagRows.forEach(f -> defaults.put(f.getKey(), f.isDefaultOn()));

        var visible = visibleSchools(caller);
        var bySchool = new LinkedHashMap<String, Map<String, Boolean>>();
        visible.forEach(s -> bySchool.put(s.getId(), new LinkedHashMap<>(defaults)));
        var stored = visible.size() == 1 ? overrides.findBySchoolId(visible.get(0).getId()) : overrides.findAll();
        for (var row : stored) {
            var values = bySchool.get(row.getSchoolId());
            if (values != null && defaults.containsKey(row.getFlagKey())) values.put(row.getFlagKey(), row.isEnabled());
        }
        var rows = visible.stream().map(s -> new FlagDto.SchoolFlags(s.getId(), s.getName(), bySchool.get(s.getId()))).toList();
        return new FlagDto.FlagMatrix(definitions, rows);
    }

    /** `GET /schools/{id}/flags` (public): all 14 keys for one school. */
    public Map<String, Boolean> forSchool(String schoolId) {
        requireSchool(schoolId);
        return flags.effective(schoolId);
    }

    /** `PUT /admin/schools/{id}/flags/{key}`: one cell of the matrix. */
    @Transactional
    public Map<String, Boolean> set(Principals.User actor, String schoolId, String key, boolean enabled) {
        var school = requireSchool(schoolId);
        flags.definition(key);
        write(school.getId(), key, enabled, actor);
        record(key, school.getId(), enabled, actor);
        flags.invalidate(school.getId());
        return flags.effective(school.getId());
    }

    /**
     * `PUT /admin/flags/{key}/all`: the column action. Every school gets an explicit row — leaving them to the
     * default would make a later change of `default_on` silently undo this — and the event is <em>one</em> audit
     * row with `school_id` null, which is what that null means (§4).
     *
     * <p>Two write statements whatever the number of tenants: one bulk update over the schools that already have a
     * row, one `INSERT … SELECT` for the schools that had none. A read-modify-write per school was linear in the
     * tenant count, which is the one number this endpoint is meant to be indifferent to.
     */
    @Transactional
    public FlagDto.FlagMatrix setForAll(Principals.User actor, String key, boolean enabled) {
        flags.definition(key);
        var now = Instant.now();
        String actorId = actor == null ? null : actor.userId();
        overrides.updateEverySchool(key, enabled, actorId, now);
        overrides.insertMissingSchools(key, enabled, actorId, now);
        em.clear();                                                     // the bulk statements bypassed the context
        record(key, null, enabled, actor);
        flags.invalidateAll();
        return matrix(actor);
    }

    /** `GET /admin/flags/audit?limit=`: newest first. */
    public List<FlagDto.FlagAuditEntry> audit(int limit) {
        int capped = Math.clamp(limit <= 0 ? DEFAULT_AUDIT_LIMIT : limit, 1, MAX_AUDIT_LIMIT);
        var rows = audit.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, capped));
        var schoolNames = new LinkedHashMap<String, String>();
        schools.findAll().forEach(s -> schoolNames.put(s.getId(), s.getName()));

        // One lookup for the whole page, not one per row: the same Admin usually flipped most of them, and a
        // `findById` per row outside a transaction is a query per row however few distinct people there are.
        var actorIds = rows.stream().map(Entities.FlagAuditEntity::getActorUserId).filter(Objects::nonNull).distinct().toList();
        var emails = new LinkedHashMap<String, String>();
        if (!actorIds.isEmpty()) users.findAllById(actorIds).forEach(u -> emails.put(u.getId(), u.getEmail()));

        var out = new ArrayList<FlagDto.FlagAuditEntry>(rows.size());
        for (var row : rows) {
            out.add(new FlagDto.FlagAuditEntry(row.getId(), row.getFlagKey(), row.getSchoolId(),
                    row.getSchoolId() == null ? null : schoolNames.get(row.getSchoolId()), row.isEnabled(),
                    row.getActorUserId(), emails.get(row.getActorUserId()),
                    row.getCreatedAt() == null ? 0 : row.getCreatedAt().toEpochMilli()));
        }
        return out;
    }

    private List<SchoolEntity> visibleSchools(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        if (caller.isAdmin()) return schools.findAll().stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
        return caller.schoolId() == null ? List.of() : schools.findById(caller.schoolId()).map(List::of).orElseGet(List::of);
    }

    private SchoolEntity requireSchool(String schoolId) {
        return schools.findById(schoolId == null ? "" : schoolId).orElseThrow(() -> ApiException.notFound("school"));
    }

    private void write(String schoolId, String key, boolean enabled, Principals.User actor) {
        var row = overrides.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled);
        row.setUpdatedBy(actor == null ? null : actor.userId());
        row.setUpdatedAt(Instant.now());
        overrides.save(row);
    }

    private void record(String key, String schoolId, boolean enabled, Principals.User actor) {
        var row = new Entities.FlagAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setFlagKey(key); row.setSchoolId(schoolId); row.setEnabled(enabled);
        row.setActorUserId(actor == null ? null : actor.userId());
        row.setCreatedAt(Instant.now());
        audit.save(row);
    }
}
