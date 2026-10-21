package quest.server.evaluations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.evaluations.Entities.EvaluationRoundEntity;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.TenantContext;

/**
 * Feature 33's rounds: the Admin's `/admin/evaluation-rounds` page, the round a request means, and the one-way
 * release to the parents.
 *
 * <p><strong>There are no terms in the code.</strong> A round is therefore a row the Admin creates — a name in both
 * languages, the day it opens, a deadline and the reminder's lead — and not something derived from a calendar the
 * school does not have.
 *
 * <p><strong>The deadline is the school's own.</strong> She may enter it as a local date and time
 * (`2026-11-05T12:00`), which is read in {@link SchoolCalendar}'s zone for the school, or as a full instant; it is
 * stored as an instant either way, so on time and late mean the same thing to every reader whatever zone she is in.
 *
 * <p><strong>Released once.</strong> Until the release no parent sees anything of the round, and the Admin may edit
 * it; afterwards both are closed — an edit and a second release are both 409. The release itself tells each parent
 * once per child ({@link NotificationService#parentsOfEvaluations}), and an evaluation approved after it is told
 * separately ({@link ChildEvaluationService#review}).
 *
 * <p><strong>Scope.</strong> The school is {@link TenantContext#writeSchoolId()} on a request and the job's own
 * argument otherwise, never a request field; every query names it.
 */
@Service
public class EvaluationRounds {
    static final int REMIND_HOURS_DEFAULT = 24, REMIND_HOURS_MAX = 168, NAME_MAX = 120;

    private final EvaluationRoundRepository rounds; private final ChildEvaluationRepository evaluations;
    private final ChildRepository children; private final NotificationService notifications;
    private final SchoolCalendar calendar; private final UserRepository users; private final AuditService audit;
    private final TenantContext tenant; private final Clock clock;

    public EvaluationRounds(EvaluationRoundRepository rounds, ChildEvaluationRepository evaluations,
                            ChildRepository children, NotificationService notifications, SchoolCalendar calendar,
                            UserRepository users, AuditService audit, TenantContext tenant, Clock clock) {
        this.rounds = rounds; this.evaluations = evaluations; this.children = children;
        this.notifications = notifications; this.calendar = calendar; this.users = users; this.audit = audit;
        this.tenant = tenant; this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public List<EvaluationRoundEntity> all(String schoolId) { return rounds.findBySchoolIdOrderByDueAtDesc(schoolId); }

    /** One round of the caller's school, or 404 — another school's id is `Optional.empty()` and answers the same. */
    public EvaluationRoundEntity require(String schoolId, String id) {
        return rounds.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("evaluation round"));
    }

    /**
     * The round a `?round=` names, or — with none — the one that is running now: the round whose deadline is the
     * next one still ahead among those that have opened, and failing that the newest round the school has. Null
     * when the school has created none, which is what an empty page answers with.
     */
    public EvaluationRoundEntity resolve(String schoolId, String requested) {
        if (requested != null && !requested.isBlank()) return require(schoolId, requested.trim());
        var all = all(schoolId);
        if (all.isEmpty()) return null;
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone(schoolId));
        EvaluationRoundEntity open = null;
        for (var round : all) {                                                 // newest deadline first
            if (round.getOpensOn().isAfter(today)) continue;
            if (!round.getDueAt().isBefore(now)) open = round;                   // the last one still ahead wins
        }
        if (open != null) return open;
        for (var round : all) if (!round.getOpensOn().isAfter(today)) return round;
        return all.getFirst();
    }

    // ---------------------------------------------------------------- the Admin's page

    public List<EvaluationDto.EvaluationRound> list() {
        String schoolId = tenant.writeSchoolId();
        var names = new HashMap<String, String>();
        var out = new ArrayList<EvaluationDto.EvaluationRound>();
        for (var round : all(schoolId)) out.add(view(round, schoolId, names));
        return List.copyOf(out);
    }

    @Transactional
    public EvaluationDto.EvaluationRound create(Principals.User caller, EvaluationDto.CreateEvaluationRoundRequest request) {
        String schoolId = tenant.writeSchoolId();
        if (request == null) throw ApiException.badRequest("Name the round and its deadline.");
        var row = new EvaluationRoundEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setNameEn(name(request.nameEn(), "nameEn")); row.setNameAr(name(request.nameAr(), "nameAr"));
        row.setOpensOn(request.opensOn() == null || request.opensOn().isBlank()
                ? LocalDate.ofInstant(clock.instant(), zone(schoolId)) : date(request.opensOn()));
        row.setDueAt(instant(request.dueAt(), zone(schoolId)));
        row.setRemindHoursBefore(hours(request.remindHoursBefore() == null ? REMIND_HOURS_DEFAULT : request.remindHoursBefore()));
        row.setCreatedBy(caller == null ? null : caller.userId());
        row.setCreatedAt(Instant.now(clock)); row.setUpdatedAt(Instant.now(clock));
        rounds.save(row);
        audit.record(caller == null ? null : caller.userId(), "evaluation.round.create", "evaluationRound", row.getId(),
                schoolId, Map.of("nameEn", row.getNameEn(), "dueAt", row.getDueAt().toString()));
        return view(row, schoolId, new HashMap<>());
    }

    @Transactional
    public EvaluationDto.EvaluationRound update(Principals.User caller, String id, EvaluationDto.UpdateEvaluationRoundRequest request) {
        String schoolId = tenant.writeSchoolId();
        var row = require(schoolId, id);
        if (row.isReleased())
            throw ApiException.conflict("already_released", "That round has been released to the parents; create a new one instead.");
        if (request != null) {
            if (request.nameEn() != null) row.setNameEn(name(request.nameEn(), "nameEn"));
            if (request.nameAr() != null) row.setNameAr(name(request.nameAr(), "nameAr"));
            if (request.opensOn() != null) row.setOpensOn(date(request.opensOn()));
            if (request.dueAt() != null) row.setDueAt(instant(request.dueAt(), zone(schoolId)));
            if (request.remindHoursBefore() != null) row.setRemindHoursBefore(hours(request.remindHoursBefore()));
        }
        row.setUpdatedAt(Instant.now(clock));
        rounds.save(row);
        audit.record(caller == null ? null : caller.userId(), "evaluation.round.update", "evaluationRound", row.getId(),
                schoolId, Map.of("dueAt", row.getDueAt().toString()));
        return view(row, schoolId, new HashMap<>());
    }

    /**
     * `POST /admin/evaluation-rounds/{id}/release`: the approved evaluations of this round become readable by the
     * parents, and each parent is told once per child. Released twice is 409 — the notification is written after the
     * commit and keyed once, so even a release the database accepted twice could not tell anybody twice.
     */
    @Transactional
    public EvaluationDto.EvaluationRound release(Principals.User caller, String id) {
        String schoolId = tenant.writeSchoolId();
        var row = require(schoolId, id);
        if (row.isReleased()) throw ApiException.conflict("already_released", "That round has already been released.");
        row.setReleasedAt(Instant.now(clock)); row.setReleasedBy(caller == null ? null : caller.userId());
        row.setUpdatedAt(Instant.now(clock));
        rounds.save(row);
        audit.record(caller == null ? null : caller.userId(), "evaluation.round.release", "evaluationRound", row.getId(),
                schoolId, Map.of("releasedAt", row.getReleasedAt().toString()));
        notifications.parentsOfEvaluations(schoolId, childrenWithApproved(schoolId, row.getId()), row.getId());
        return view(row, schoolId, new HashMap<>());
    }

    /** One child per family row to tell: the children this round has an approved evaluation of, each once. */
    private List<quest.server.children.Entities.ChildEntity> childrenWithApproved(String schoolId, String roundId) {
        var ids = new java.util.LinkedHashSet<String>();
        for (var row : evaluations.findBySchoolIdAndRoundId(schoolId, roundId))
            if (EvaluationDto.APPROVED.equals(row.getState())) ids.add(row.getChildId());
        var out = new ArrayList<quest.server.children.Entities.ChildEntity>();
        // `findOneById` and never `findById`: a Hibernate filter does not apply to `em.find`, and this row decides
        // whose parent is told.
        for (String childId : ids) children.findOneById(childId).filter(c -> c.getDeletedAt() == null).ifPresent(out::add);
        return out;
    }

    // ---------------------------------------------------------------- views and parsing

    public EvaluationDto.EvaluationRound view(EvaluationRoundEntity round, String schoolId, Map<String, String> names) {
        if (round == null) return null;
        return new EvaluationDto.EvaluationRound(round.getId(), round.getNameEn(), round.getNameAr(),
                round.getOpensOn().toString(), round.getDueAt().toString(), round.getRemindHoursBefore(),
                zone(schoolId).getId(), round.isReleased(),
                round.getReleasedAt() == null ? null : round.getReleasedAt().toString(),
                round.getReleasedBy(), name(round.getReleasedBy(), names));
    }

    /** Every round of the school as the picker reads them, newest deadline first. */
    public List<EvaluationDto.EvaluationRound> views(String schoolId) {
        var names = new HashMap<String, String>();
        var out = new ArrayList<EvaluationDto.EvaluationRound>();
        for (var round : all(schoolId)) out.add(view(round, schoolId, names));
        return List.copyOf(out);
    }

    public ZoneId zone(String schoolId) { return calendar.of(schoolId).zone(); }

    private String name(String userId, Map<String, String> cache) {
        if (userId == null) return null;
        return cache.computeIfAbsent(userId, id -> users.findById(id)
                .map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName())
                .orElse(null));
    }

    private static String name(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("`" + field + "` is the round's name.");
        String out = value.trim();
        return out.length() <= NAME_MAX ? out : out.substring(0, NAME_MAX);
    }

    static LocalDate date(String value) {
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("`opensOn` is a date, as 2026-11-01."); }
    }

    /** A full instant, or a local date and time read in the school's own zone — never the server's. */
    static Instant instant(String value, ZoneId zone) {
        if (value == null || value.isBlank())
            throw ApiException.badRequest("`dueAt` is the deadline, as 2026-11-05T12:00 in the school's zone.");
        String text = value.trim();
        try { return OffsetDateTime.parse(text).toInstant(); } catch (DateTimeParseException ignored) { /* a local one, then */ }
        try { return LocalDateTime.parse(text).atZone(zone).toInstant(); }
        catch (DateTimeParseException e) {
            throw ApiException.badRequest("`dueAt` is the deadline, as 2026-11-05T12:00 in the school's zone.");
        }
    }

    private static int hours(int value) {
        if (value < 1 || value > REMIND_HOURS_MAX)
            throw ApiException.badRequest("`remindHoursBefore` is between 1 and " + REMIND_HOURS_MAX + " hours.");
        return value;
    }
}
