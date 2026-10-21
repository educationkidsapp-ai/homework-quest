package quest.server.evaluations;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dashboard.EvaluationLink;
import quest.api.dto.EvaluationCriterion;
import quest.api.dto.NotificationKind;
import quest.api.dto.ParentEvaluation;
import quest.api.dto.TeamLatestEvaluation;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.evaluations.Entities.ChildEvaluationEntity;
import quest.server.evaluations.Entities.EvaluationRoundEntity;
import quest.server.notifications.NotificationService;
import quest.server.staffattendance.StaffAttendanceDto;
import quest.server.staffattendance.StaffBoardScope;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * Feature 33: the teacher's own evaluations, the leaders' tracking board over them, and what the parent reads.
 *
 * <p><strong>What is expected is what she teaches.</strong> One row per (class, subject) of `teaching_assignments`
 * times the children placed in that class — never a hand-kept list — so moving an assignment moves the expectation
 * with it. {@link TeacherScope#requireAssignment} guards every write, and the child must be placed in that very
 * class, so a class, a subject or a child that is not hers is refused however the request is shaped.
 *
 * <p><strong>The leaders see feature 17's rows and nothing else.</strong> {@link StaffBoardScope} is
 * {@code staffattendance.BoardScope} itself, so this board and the attendance board can never drift apart; a
 * teacher outside the view is 404, the same answer as an id that does not exist. Feature 31's rule, one feature
 * over.
 *
 * <p><strong>Two words, one row.</strong> {@code state} is the workflow (draft → submitted → returned or approved)
 * and is stored; the compliance status (missing, on time, late) is computed against the round's deadline, so an
 * Admin who moves it re-reads every row. A draft is <em>missing</em>: nothing has been handed in.
 *
 * <p><strong>The parent reads words.</strong> {@link #parentEvaluations} answers only approved evaluations of
 * released rounds and maps each rating to a word key here, on the server, so there is no number, no draft, no state
 * and no reviewer in what leaves this class for a parent ({@link ParentEvaluation}).
 */
@Service
public class ChildEvaluationService {
    private static final int TEXT_MAX = 2000, COMMENT_MAX = 1000;

    private final ChildEvaluationRepository evaluations; private final EvaluationRounds rounds;
    private final EvaluationRoundRepository roundRows;
    private final ChildRepository children; private final ClassRepository sections;
    private final StaffBoardScope boardScope; private final TeacherScope teacherScope;
    private final NotificationService notifications; private final UserRepository users;
    private final TenantContext tenant; private final Clock clock;

    public ChildEvaluationService(ChildEvaluationRepository evaluations, EvaluationRounds rounds,
                                  EvaluationRoundRepository roundRows,
                                  ChildRepository children, ClassRepository sections, StaffBoardScope boardScope,
                                  TeacherScope teacherScope, NotificationService notifications, UserRepository users,
                                  TenantContext tenant, Clock clock) {
        this.evaluations = evaluations; this.rounds = rounds; this.roundRows = roundRows;
        this.children = children; this.sections = sections;
        this.boardScope = boardScope; this.teacherScope = teacherScope; this.notifications = notifications;
        this.users = users; this.tenant = tenant; this.clock = clock;
    }

    // ---------------------------------------------------------------- the teacher's own page

    /** `GET /teacher/evaluations?round=`: every child she is expected to evaluate in that round, with its status. */
    public EvaluationDto.TeacherEvaluations mine(Principals.User caller, String requestedRound) {
        String schoolId = tenant.writeSchoolId();
        String teacherId = TeacherScope.require(caller).userId();
        var round = rounds.resolve(schoolId, requestedRound);
        if (round == null)
            return new EvaluationDto.TeacherEvaluations(null, List.of(), counts(List.of()), List.of());
        var rows = rowsOf(schoolId, teacherId, round);
        return new EvaluationDto.TeacherEvaluations(rounds.view(round, schoolId, new HashMap<>()),
                rounds.views(schoolId), counts(rows), rows);
    }

    /** `GET /teacher/evaluations/{roundId}/{classId}/{subject}/{childId}`: one row, hers, saved or still empty. */
    public EvaluationDto.ChildEvaluationRow one(Principals.User caller, String roundId, String classId, String subject,
                                                String childId) {
        String schoolId = tenant.writeSchoolId();
        String teacherId = TeacherScope.require(caller).userId();
        var round = rounds.require(schoolId, roundId);
        var target = target(caller, classId, subject, childId);
        var row = evaluations.findOneBySchoolIdAndRoundIdAndTeacherIdAndClassIdAndSubjectAndChildId(
                schoolId, round.getId(), teacherId, target.classId(), target.subject(), target.child().getId()).orElse(null);
        return row(round, target, row, new HashMap<>());
    }

    /** `PUT` on the same path: her draft, saved as often as she likes. A submitted or approved row answers 409. */
    @Transactional
    public EvaluationDto.ChildEvaluationRow save(Principals.User caller, String roundId, String classId, String subject,
                                                 String childId, EvaluationDto.SaveChildEvaluationRequest request) {
        String schoolId = tenant.writeSchoolId();
        String teacherId = TeacherScope.require(caller).userId();
        var round = rounds.require(schoolId, roundId);
        var target = target(caller, classId, subject, childId);
        var row = editable(schoolId, round, teacherId, target);
        apply(row, request);
        // A returned evaluation stays returned while she works on it: the leader's comment is what she is reading,
        // and the board should go on saying it came back until she submits it again.
        if (!EvaluationDto.RETURNED.equals(row.getState())) { row.setState(EvaluationDto.DRAFT); row.setSubmittedAt(null); }
        row.setUpdatedAt(Instant.now(clock));
        evaluations.save(row);
        return row(round, target, row, new HashMap<>());
    }

    /**
     * `POST …/submit`: all four criteria and a comment are required — an evaluation a parent may one day read is not
     * a half-filled form. A submitted or approved row answers 409; a returned one is submitted again, which is the
     * point of returning it.
     */
    @Transactional
    public EvaluationDto.ChildEvaluationRow submit(Principals.User caller, String roundId, String classId, String subject,
                                                   String childId, EvaluationDto.SaveChildEvaluationRequest request) {
        String schoolId = tenant.writeSchoolId();
        String teacherId = TeacherScope.require(caller).userId();
        var round = rounds.require(schoolId, roundId);
        var target = target(caller, classId, subject, childId);
        var row = editable(schoolId, round, teacherId, target);
        if (request != null) apply(row, request);
        for (String criterion : EvaluationDto.CRITERIA)
            if (ratingOf(row, criterion) == null)
                throw ApiException.badRequest("Rate all four criteria before submitting (" + criterion + " is missing).");
        if (row.getComment() == null) throw ApiException.badRequest("Write a comment before submitting.");
        row.setState(EvaluationDto.SUBMITTED); row.setSubmittedAt(Instant.now(clock));
        row.setReviewedBy(null); row.setReviewedAt(null); row.setReviewComment(null);
        row.setUpdatedAt(Instant.now(clock));
        evaluations.save(row);
        return row(round, target, row, new HashMap<>());
    }

    // ---------------------------------------------------------------- the leaders' board

    /** `GET /evaluations/board?round=`: one row per teacher the caller leads, counts only. */
    public EvaluationDto.EvaluationBoard board(Principals.User caller, String requestedRound) {
        return board(caller, requestedRound, null);
    }

    /** `GET /evaluations/board/{teacherId}?round=`: one teacher's own evaluations, ratings and comments included. */
    public EvaluationDto.EvaluationBoard boardOf(Principals.User caller, String teacherId, String requestedRound) {
        String schoolId = tenant.writeSchoolId();
        boardScope.one(schoolId, caller, StaffAttendanceDto.KIND_USER, teacherId);   // 404 outside her view
        return board(caller, requestedRound, teacherId);
    }

    private EvaluationDto.EvaluationBoard board(Principals.User caller, String requestedRound, String onlyTeacher) {
        String schoolId = tenant.writeSchoolId();
        var view = boardScope.of(schoolId, caller);
        var round = rounds.resolve(schoolId, requestedRound);
        if (round == null)
            return new EvaluationDto.EvaluationBoard(null, List.of(), view.scope(), counts(List.of()), List.of());

        var byTeacher = new LinkedHashMap<String, List<ChildEvaluationEntity>>();
        for (var row : evaluations.findBySchoolIdAndRoundId(schoolId, round.getId()))
            byTeacher.computeIfAbsent(row.getTeacherId(), t -> new ArrayList<>()).add(row);
        var names = new HashMap<String, String>();

        var rows = new ArrayList<EvaluationDto.EvaluationBoardRow>();
        var all = new ArrayList<EvaluationDto.ChildEvaluationRow>();
        for (var employee : view.employees()) {
            if (!StaffAttendanceDto.KIND_USER.equals(employee.kind())) continue;      // a worker evaluates nobody
            if (onlyTeacher != null && !onlyTeacher.equals(employee.id())) continue;
            var own = rows(schoolId, employee.id(), round, byTeacher.getOrDefault(employee.id(), List.of()), names);
            if (own.isEmpty()) continue;                                              // no assignment, nothing expected
            all.addAll(own);
            rows.add(new EvaluationDto.EvaluationBoardRow(employee.id(), employee.fullName(), counts(own),
                    onlyTeacher == null ? List.of() : own));
        }
        return new EvaluationDto.EvaluationBoard(rounds.view(round, schoolId, names), rounds.views(schoolId),
                view.scope(), counts(all), List.copyOf(rows));
    }

    /**
     * `POST /evaluations/{id}/review`: approve a submitted evaluation, or return it with a comment. The first
     * decision wins and names the leader and the time; a second is 409, as is a decision on a row nobody has
     * submitted.
     *
     * <p>A return notifies the teacher (`evaluation.returned`). An approval <strong>after the round was
     * released</strong> notifies the child's parent at once, because the row becomes readable the moment it is
     * approved — the spec's "an evaluation approved after the release appears straight away and is notified too".
     */
    @Transactional
    public EvaluationDto.ChildEvaluationRow review(Principals.User caller, String id,
                                                   EvaluationDto.ReviewEvaluationRequest request) {
        String schoolId = tenant.writeSchoolId();
        var row = evaluations.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("evaluation"));
        boardScope.one(schoolId, caller, StaffAttendanceDto.KIND_USER, row.getTeacherId());
        var round = rounds.require(schoolId, row.getRoundId());
        boolean approve = decision(request);
        if (EvaluationDto.APPROVED.equals(row.getState()) || EvaluationDto.RETURNED.equals(row.getState()))
            throw ApiException.conflict("already_reviewed", "That evaluation has already been reviewed.");
        if (!EvaluationDto.SUBMITTED.equals(row.getState()))
            throw ApiException.conflict("not_submitted", "That evaluation has not been submitted yet.");
        row.setReviewedBy(caller == null ? null : caller.userId()); row.setReviewedAt(Instant.now(clock));
        row.setReviewComment(clip(trim(request == null ? null : request.comment()), COMMENT_MAX));
        row.setState(approve ? EvaluationDto.APPROVED : EvaluationDto.RETURNED);
        row.setUpdatedAt(Instant.now(clock));
        evaluations.save(row);

        if (approve) {
            if (round.isReleased())
                children.findOneById(row.getChildId()).filter(c -> c.getDeletedAt() == null)
                        .ifPresent(child -> notifications.parentsOfEvaluations(schoolId, List.of(child), row.getId()));
        } else {
            notifications.staffNotice(schoolId, row.getTeacherId(), NotificationKind.EVALUATION_RETURNED,
                    "Evaluation returned", "One evaluation was sent back to you with a comment.",
                    "تم إرجاع تقييم", "أُعيد إليك تقييم واحد مع ملاحظة.",
                    link(round.getId(), EvaluationLink.RETURNED, null), "evaluation:returned:" + row.getId());
        }
        var target = new Target(row.getClassId(), row.getSubject(), children.findOneById(row.getChildId()).orElse(null));
        return row(round, target, row, new HashMap<>());
    }

    // ---------------------------------------------------------------- the parent

    /**
     * `GET /children/{id}/evaluations`: the approved evaluations of released rounds, newest round first and then by
     * subject. The caller has already proved the child is hers ({@link quest.server.chat.ChatService#placed} or the
     * controller's own check), so the query names the child and never a school parameter — she carries no tenant
     * scope at all.
     */
    public List<ParentEvaluation> parentEvaluations(ChildEntity child) {
        var out = new ArrayList<ParentEvaluation>();
        var roundsById = new HashMap<String, EvaluationRoundEntity>();
        var names = new HashMap<String, String>();
        for (var row : evaluations.findByChildIdAndStateOrderBySubjectAsc(child.getId(), EvaluationDto.APPROVED)) {
            var round = round(roundsById, row.getRoundId());
            if (round == null || !round.isReleased()) continue;                      // nothing before the release
            out.add(new ParentEvaluation(round.getId(), round.getNameEn(), round.getNameAr(), row.getSubject(),
                    row.getTeacherId(), teacherName(names, row.getTeacherId()), criteria(row),
                    row.getComment(), row.getGoingWell(), row.getNextStep(), round.getReleasedAt().toString()));
        }
        out.sort(Comparator.comparing((ParentEvaluation e) -> releasedAt(roundsById, e.getRoundId())).reversed()
                .thenComparing(ParentEvaluation::getSubject));
        return List.copyOf(out);
    }

    /**
     * Feature 32's hook: the newest evaluation of this child that one teacher has written and the parent may read.
     * Released and approved data only, and no rating or comment — it is a cue to open the evaluations screen.
     */
    public Map<String, TeamLatestEvaluation> latestEvaluations(ChildEntity child) {
        var roundsById = new HashMap<String, EvaluationRoundEntity>();
        var newest = new LinkedHashMap<String, TeamLatestEvaluation>();
        var when = new HashMap<String, Instant>();
        for (var row : evaluations.findByChildIdAndStateOrderBySubjectAsc(child.getId(), EvaluationDto.APPROVED)) {
            var round = round(roundsById, row.getRoundId());
            if (round == null || !round.isReleased()) continue;
            var previous = when.get(row.getTeacherId());
            if (previous != null && !round.getReleasedAt().isAfter(previous)) continue;
            when.put(row.getTeacherId(), round.getReleasedAt());
            newest.put(row.getTeacherId(), new TeamLatestEvaluation(round.getNameEn(), round.getNameAr(), row.getSubject()));
        }
        return Map.copyOf(newest);
    }

    /** The four criteria as words, in the order every screen reads them. A null rating is simply left out. */
    private static List<EvaluationCriterion> criteria(ChildEvaluationEntity row) {
        var out = new ArrayList<EvaluationCriterion>(EvaluationDto.CRITERIA.size());
        for (String criterion : EvaluationDto.CRITERIA) {
            Short value = ratingOf(row, criterion);
            if (value != null) out.add(new EvaluationCriterion(criterion, word(value)));
        }
        return List.copyOf(out);
    }

    /** 4 … 1 as the word key a client translates. The number itself never leaves the server for a parent. */
    static String word(short rating) {
        return switch (rating) {
            case 4 -> EvaluationDto.EXCELLENT;
            case 3 -> EvaluationDto.GOOD;
            case 2 -> EvaluationDto.DEVELOPING;
            default -> EvaluationDto.NEEDS_SUPPORT;
        };
    }

    // ---------------------------------------------------------------- rows

    /** One (class, subject, child) the caller may write about: hers by assignment, and the child placed in it. */
    private record Target(String classId, String subject, ChildEntity child) {}

    private Target target(Principals.User caller, String classId, String subject, String childId) {
        String wanted = normalise(subject);
        teacherScope.requireAssignment(caller, classId, wanted);
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("child"));
        if (!classId.equals(child.getClassId()))
            throw ApiException.forbidden("That child is not in that class.");
        return new Target(classId, wanted, child);
    }

    /** Her row for that child, or a fresh one — and 409 when it is no longer hers to edit. */
    private ChildEvaluationEntity editable(String schoolId, EvaluationRoundEntity round, String teacherId, Target target) {
        var row = evaluations.findOneBySchoolIdAndRoundIdAndTeacherIdAndClassIdAndSubjectAndChildId(
                schoolId, round.getId(), teacherId, target.classId(), target.subject(), target.child().getId())
                .orElseGet(ChildEvaluationEntity::new);
        if (row.getId() == null) {
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setRoundId(round.getId());
            row.setTeacherId(teacherId); row.setClassId(target.classId()); row.setSubject(target.subject());
            row.setChildId(target.child().getId()); row.setState(EvaluationDto.DRAFT);
            row.setCreatedAt(Instant.now(clock)); row.setUpdatedAt(Instant.now(clock));
            return row;
        }
        if (EvaluationDto.SUBMITTED.equals(row.getState()))
            throw ApiException.conflict("already_submitted", "That evaluation has been submitted; ask your leader to return it.");
        if (EvaluationDto.APPROVED.equals(row.getState()))
            throw ApiException.conflict("already_approved", "That evaluation has been approved and cannot be changed.");
        return row;
    }

    private static void apply(ChildEvaluationEntity row, EvaluationDto.SaveChildEvaluationRequest request) {
        if (request == null) return;
        if (request.participation() != null) row.setParticipation(rating(request.participation(), EvaluationDto.PARTICIPATION));
        if (request.effort() != null) row.setEffort(rating(request.effort(), EvaluationDto.EFFORT));
        if (request.behaviour() != null) row.setBehaviour(rating(request.behaviour(), EvaluationDto.BEHAVIOUR));
        if (request.understanding() != null) row.setUnderstanding(rating(request.understanding(), EvaluationDto.UNDERSTANDING));
        if (request.comment() != null) row.setComment(clip(trim(request.comment()), TEXT_MAX));
        if (request.goingWell() != null) row.setGoingWell(clip(trim(request.goingWell()), TEXT_MAX));
        if (request.nextStep() != null) row.setNextStep(clip(trim(request.nextStep()), TEXT_MAX));
    }

    private List<EvaluationDto.ChildEvaluationRow> rowsOf(String schoolId, String teacherId, EvaluationRoundEntity round) {
        return rows(schoolId, teacherId, round,
                evaluations.findBySchoolIdAndRoundIdAndTeacherId(schoolId, round.getId(), teacherId), new HashMap<>());
    }

    /**
     * One teacher's expected rows for a round: her assignments in class then subject order, each times the children
     * placed in that class, with the row she has written or none.
     */
    private List<EvaluationDto.ChildEvaluationRow> rows(String schoolId, String teacherId, EvaluationRoundEntity round,
                                                        List<ChildEvaluationEntity> written, Map<String, String> names) {
        var byKey = new HashMap<String, ChildEvaluationEntity>();
        for (var row : written) byKey.put(key(row.getClassId(), row.getSubject(), row.getChildId()), row);
        var out = new ArrayList<EvaluationDto.ChildEvaluationRow>();
        var roster = new HashMap<String, List<ChildEntity>>();
        for (var assignment : expected(teacherId)) {
            String subject = normalise(assignment.getSubject());
            for (var child : roster.computeIfAbsent(assignment.getClassId(),
                    id -> children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(id))) {
                var target = new Target(assignment.getClassId(), subject, child);
                out.add(row(round, target, byKey.get(key(assignment.getClassId(), subject, child.getId())), names));
            }
        }
        return List.copyOf(out);
    }

    private List<TeachingAssignmentEntity> expected(String teacherId) {
        var own = new ArrayList<>(teacherScope.assignmentsOf(teacherId));
        own.sort(Comparator.comparing(TeachingAssignmentEntity::getClassId).thenComparing(a -> normalise(a.getSubject())));
        return own;
    }

    private EvaluationDto.ChildEvaluationRow row(EvaluationRoundEntity round, Target target,
                                                 ChildEvaluationEntity row, Map<String, String> names) {
        String childId = target.child() == null ? null : target.child().getId();
        String childName = target.child() == null ? null : target.child().getName();
        String className = className(target.classId(), names);
        if (row == null)
            return new EvaluationDto.ChildEvaluationRow(null, round.getId(), target.classId(), className,
                    target.subject(), childId, childName, EvaluationDto.MISSING, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        return new EvaluationDto.ChildEvaluationRow(row.getId(), round.getId(), target.classId(), className,
                target.subject(), childId, childName, status(round, row), row.getState(),
                number(row.getParticipation()), number(row.getEffort()), number(row.getBehaviour()),
                number(row.getUnderstanding()), row.getComment(), row.getGoingWell(), row.getNextStep(),
                row.getSubmittedAt() == null ? null : row.getSubmittedAt().toString(),
                row.getReviewedBy(), name(names, row.getReviewedBy()),
                row.getReviewedAt() == null ? null : row.getReviewedAt().toString(), row.getReviewComment());
    }

    /** Missing while nothing has been submitted (a draft included), then the deadline, the return or the approval. */
    static String status(EvaluationRoundEntity round, ChildEvaluationEntity row) {
        if (row == null) return EvaluationDto.MISSING;
        return switch (row.getState()) {
            case EvaluationDto.APPROVED -> EvaluationDto.APPROVED;
            case EvaluationDto.RETURNED -> EvaluationDto.RETURNED;
            case EvaluationDto.SUBMITTED -> row.getSubmittedAt() != null && row.getSubmittedAt().isAfter(round.getDueAt())
                    ? EvaluationDto.LATE : EvaluationDto.ON_TIME;
            default -> EvaluationDto.MISSING;
        };
    }

    private static EvaluationDto.EvaluationCounts counts(List<EvaluationDto.ChildEvaluationRow> rows) {
        int onTime = 0, late = 0, missing = 0, returned = 0, approved = 0;
        for (var r : rows) switch (r.status()) {
            case EvaluationDto.ON_TIME -> onTime++;
            case EvaluationDto.LATE -> late++;
            case EvaluationDto.RETURNED -> returned++;
            case EvaluationDto.APPROVED -> approved++;
            default -> missing++;
        }
        return new EvaluationDto.EvaluationCounts(onTime, late, missing, returned, approved);
    }

    // ---------------------------------------------------------------- small things

    static String link(String roundId, String type, Integer missing) {
        var parts = new ArrayList<String>();
        parts.add(EvaluationLink.TYPE + "=" + type);
        parts.add(EvaluationLink.ROUND + "=" + java.net.URLEncoder.encode(roundId, java.nio.charset.StandardCharsets.UTF_8));
        if (missing != null) parts.add(EvaluationLink.MISSING + "=" + missing);
        return "/notifications?" + String.join("&", parts);
    }

    /**
     * One round by id alone, cached per request — the parent's read, where the id came from her own child's row and
     * she carries no tenant scope to narrow by.
     */
    private EvaluationRoundEntity round(Map<String, EvaluationRoundEntity> cache, String roundId) {
        if (cache.containsKey(roundId)) return cache.get(roundId);
        var round = roundRows.findOneById(roundId).orElse(null);
        cache.put(roundId, round);
        return round;
    }

    private static Instant releasedAt(Map<String, EvaluationRoundEntity> rounds, String roundId) {
        var round = rounds.get(roundId);
        return round == null || round.getReleasedAt() == null ? Instant.EPOCH : round.getReleasedAt();
    }

    private static boolean decision(EvaluationDto.ReviewEvaluationRequest request) {
        String value = request == null || request.decision() == null ? "" : request.decision().trim().toUpperCase(Locale.ROOT);
        if (EvaluationDto.APPROVE_DECISION.equals(value)) return true;
        if (EvaluationDto.RETURN_DECISION.equals(value)) return false;
        throw ApiException.badRequest("`decision` is APPROVE or RETURN.");
    }

    private static Short ratingOf(ChildEvaluationEntity row, String criterion) {
        return switch (criterion) {
            case EvaluationDto.PARTICIPATION -> row.getParticipation();
            case EvaluationDto.EFFORT -> row.getEffort();
            case EvaluationDto.BEHAVIOUR -> row.getBehaviour();
            default -> row.getUnderstanding();
        };
    }

    private static short rating(int value, String field) {
        if (value < 1 || value > 4)
            throw ApiException.badRequest("`" + field + "` is 1 to 4 (needs support, developing, good, excellent).");
        return (short) value;
    }

    private static Integer number(Short value) { return value == null ? null : value.intValue(); }

    private String className(String classId, Map<String, String> cache) {
        return cache.computeIfAbsent("class:" + classId, key -> sections.findOneById(classId)
                .map(c -> c.getName() == null || c.getName().isBlank() ? c.getSubject() : c.getName()).orElse(classId));
    }

    private String name(Map<String, String> cache, String userId) {
        if (userId == null) return null;
        return cache.computeIfAbsent("user:" + userId, key -> users.findById(userId)
                .map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName())
                .orElse(null));
    }

    private String teacherName(Map<String, String> cache, String userId) {
        String name = name(cache, userId);
        return name == null ? "" : name;
    }

    private static String key(String classId, String subject, String childId) {
        return classId + ":" + subject + ":" + childId;
    }

    private static String normalise(String subject) {
        if (subject == null || subject.isBlank()) throw ApiException.badRequest("Name the subject.");
        return subject.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String clip(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
