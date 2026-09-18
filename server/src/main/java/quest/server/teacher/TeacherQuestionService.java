package quest.server.teacher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dashboard.TeacherAnswerUpload;
import quest.api.dashboard.TeacherQuestionPlay;
import quest.api.dto.Play;
import quest.api.dto.SourceKind;
import quest.api.dto.Stop;
import quest.api.dto.TeacherIsland;
import quest.api.dto.Theme;
import quest.api.validation.SchemaValidator;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TenantContext;

/**
 * §6 screen 14, "Questions to students": a teacher writes one or more §5 stops, picks which of her classes and a
 * date window, and sends them. The children of those classes see a "From your teacher" island on their map while
 * the window is open, answer the stops in the ordinary player, and she reads the results per child.
 *
 * <p><strong>The stops are validated, not trusted.</strong> They arrive as JSON, are decoded with the shared
 * kotlinx codec — which refuses an unknown field outright — and are then run through
 * {@link SchemaValidator#validate(Play, Integer, Set, boolean)} in <em>lenient</em> mode, exactly as a hand-written
 * manual play is: 1–9 stops and the exit ticket optional, but every per-type rule (the correct option is an option,
 * a sequence has one gap, an order is a permutation…) still enforced. A question whose stops do not validate is a
 * 400 naming the failures, on create, on update <em>and</em> again on send.
 *
 * <p><strong>Sending is one-way.</strong> `sent_at` is stamped once; a second send is a 409, and so is an edit after
 * it, because children may already have answered and rewriting the stops under them would orphan their rows.
 *
 * <p><strong>Who sees it.</strong> A question names class ids, and a class is (school, curriculum, grade, subject);
 * a child belongs to the classes of her school matching her curriculum and grade, any subject — the §2 rule the map
 * already uses. So a question reaches exactly the children of the chosen classes, in the chosen school, and nobody
 * else: {@link #islandsFor} starts from the child's own `school_id`, never from a parameter.
 */
@Service
public class TeacherQuestionService {
    /** A question is a level-1, variant-0 play in the app's player; the theme is fixed because there is no lesson. */
    static final Theme THEME = new Theme("Message pot", "Teacher's questions", "📣", "Sent to your teacher!");
    /** The same ceiling a manual play has: a question is a handful of stops, not a lesson. */
    private static final int MAX_STOPS = 9;
    /** A window longer than a term is a mistake, not a plan. */
    private static final int MAX_WINDOW_DAYS = 180;

    private final TeacherQuestionRepository questions; private final TeacherQuestionAnswerRepository answers;
    private final ClassRepository classes; private final ChildRepository children; private final UserRepository users;
    private final TeacherAccess access; private final TenantContext tenant; private final Json json;

    public TeacherQuestionService(TeacherQuestionRepository questions, TeacherQuestionAnswerRepository answers,
                                  ClassRepository classes, ChildRepository children, UserRepository users,
                                  TeacherAccess access, TenantContext tenant, Json json) {
        this.questions = questions; this.answers = answers; this.classes = classes; this.children = children;
        this.users = users; this.access = access; this.tenant = tenant; this.json = json;
    }

    // ---------------------------------------------------------------- the teacher's side

    /**
     * `GET /teacher/questions`: hers newest first, or the whole school's for a MANAGERIAL or ADMIN caller.
     *
     * <p>Fixed query count whatever the number of questions: the rows (1), every answer of all of them (1), and the
     * school's live children (1), grouped in Java — never a summary query per question.
     */
    public List<TeacherDto.TeacherQuestion> list(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var rows = access.isTeacher(caller)
                ? questions.findBySchoolIdAndTeacherIdOrderByCreatedAtDesc(schoolId, caller.userId())
                : questions.findBySchoolIdOrderByCreatedAtDesc(schoolId);
        if (rows.isEmpty()) return List.of();

        var ids = rows.stream().map(Entities.TeacherQuestionEntity::getId).toList();
        var byQuestion = new LinkedHashMap<String, List<Entities.TeacherQuestionAnswerEntity>>();
        for (var answer : answers.findByQuestionIdIn(ids))
            byQuestion.computeIfAbsent(answer.getQuestionId(), k -> new ArrayList<>()).add(answer);

        var roster = children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(schoolId);
        var teachers = teachersById(rows.stream().map(Entities.TeacherQuestionEntity::getTeacherId).distinct().toList());
        var classesById = classesOf(schoolId);

        var out = new ArrayList<TeacherDto.TeacherQuestion>(rows.size());
        for (var row : rows) {
            var teacher = teachers.get(row.getTeacherId());
            out.add(summary(row, teacher == null ? null : teacher.getDisplayName(),
                    byQuestion.getOrDefault(row.getId(), List.of()), audience(row, classesById, roster)));
        }
        return List.copyOf(out);
    }

    @Transactional
    public TeacherDto.TeacherQuestion create(Principals.User caller, TeacherDto.CreateTeacherQuestionRequest request) {
        var window = window(request.from(), request.to());
        var stops = validated(request.stops());
        var classIds = ownedClassIds(caller, request.classIds());

        var row = new Entities.TeacherQuestionEntity();
        row.setId(UUID.randomUUID().toString());
        row.setSchoolId(access.writeSchoolId());
        row.setTeacherId(authorOf(caller, classIds));
        row.setTitle(title(request.title()));
        row.setStopsJson(encode(stops));
        row.setClassIdsJson(json.write(classIds));
        row.setFromDate(window[0]); row.setToDate(window[1]);
        row.setCreatedAt(Instant.now());
        return summaryOf(questions.save(row));
    }

    @Transactional
    public TeacherDto.TeacherQuestion update(Principals.User caller, String id, TeacherDto.UpdateTeacherQuestionRequest request) {
        var row = mine(caller, id);
        if (row.getSentAt() != null)
            throw ApiException.conflict("That question has already been sent — children may have answered it. Send a new one instead.");

        if (request.title() != null) row.setTitle(title(request.title()));
        if (request.stops() != null) row.setStopsJson(encode(validated(request.stops())));
        if (request.classIds() != null) row.setClassIdsJson(json.write(ownedClassIds(caller, request.classIds())));
        if (request.from() != null || request.to() != null) {
            var window = window(request.from() == null ? row.getFromDate().toString() : request.from(),
                    request.to() == null ? row.getToDate().toString() : request.to());
            row.setFromDate(window[0]); row.setToDate(window[1]);
        }
        return summaryOf(questions.save(row));
    }

    /** §6 screen 14's Send: validate once more against the schema, stamp `sent_at`, and never again. */
    @Transactional
    public TeacherDto.TeacherQuestion send(Principals.User caller, String id) {
        var row = mine(caller, id);
        if (row.getSentAt() != null) throw ApiException.conflict("That question has already been sent.");
        var stops = validated(json.tree(row.getStopsJson()));
        if (stops.isEmpty()) throw ApiException.badRequest("Add at least one question before sending.");
        if (classIdsOf(row).isEmpty()) throw ApiException.badRequest("Choose at least one class before sending.");
        row.setStopsJson(encode(stops));
        row.setSentAt(Instant.now());
        return summaryOf(questions.save(row));
    }

    /** `GET /teacher/questions/{id}/results`: every child of the chosen classes, answered or not. */
    public TeacherDto.TeacherQuestionResults results(Principals.User caller, String id) {
        var row = readable(caller, id);
        var stopIds = stopIdsOf(row);
        var rows = answers.findByQuestionIdOrderByAnsweredAtAsc(row.getId());
        var byChild = new LinkedHashMap<String, List<Entities.TeacherQuestionAnswerEntity>>();
        for (var answer : rows) byChild.computeIfAbsent(answer.getChildId(), k -> new ArrayList<>()).add(answer);

        var classesById = classesOf(row.getSchoolId());
        var roster = audience(row, classesById, children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(row.getSchoolId()));

        var out = new ArrayList<TeacherDto.TeacherQuestionChildResult>(roster.size());
        for (var child : roster) {
            var mine = byChild.getOrDefault(child.getId(), List.of());
            var byStop = new HashMap<String, Entities.TeacherQuestionAnswerEntity>();
            for (var answer : mine) byStop.put(answer.getStopId(), answer);
            var perStop = new ArrayList<TeacherDto.TeacherQuestionStopResult>(stopIds.size());
            int stars = 0;
            for (String stopId : stopIds) {
                var answer = byStop.get(stopId);
                if (answer != null) stars += answer.getStars();
                perStop.add(new TeacherDto.TeacherQuestionStopResult(stopId, answer != null,
                        answer != null && answer.isCorrect(), answer == null ? 0 : answer.getStars(),
                        answer == null ? null : answer.getAnsweredAt().toEpochMilli()));
            }
            out.add(new TeacherDto.TeacherQuestionChildResult(child.getId(), child.getName(),
                    classIdFor(child, classesById, classIdsOf(row)), byStop.size(), stars, List.copyOf(perStop)));
        }
        return new TeacherDto.TeacherQuestionResults(row.getId(), row.getTitle(), stopIds, List.copyOf(out), accuracy(rows));
    }

    // ---------------------------------------------------------------- the child's side

    /**
     * The "From your teacher" islands for one child's map: every sent question of her school whose window contains
     * today and whose classes include one of hers.
     *
     * <p>Four statements whatever the number of questions — the classes of her (curriculum, grade), the active
     * questions, how many stops of each she has answered, and the teachers' names — so
     * {@link quest.server.children.MapService} stays the fixed-cost query it was.
     */
    public List<TeacherIsland> islandsFor(quest.server.children.Entities.ChildEntity child, LocalDate today) {
        var hers = classes.findBySchoolIdAndCurriculumAndGrade(child.getSchoolId(), child.getCurriculum(), child.getGrade())
                .stream().map(ClassEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (hers.isEmpty()) return List.of();

        var active = questions.findActive(child.getSchoolId(), today).stream()
                .filter(q -> classIdsOf(q).stream().anyMatch(hers::contains)).toList();
        if (active.isEmpty()) return List.of();

        var ids = active.stream().map(Entities.TeacherQuestionEntity::getId).toList();
        var answered = new HashMap<String, Integer>();
        for (Object[] pair : answers.countAnsweredByQuestion(child.getId(), ids))
            answered.put((String) pair[0], ((Number) pair[1]).intValue());
        var teachers = teachersById(active.stream().map(Entities.TeacherQuestionEntity::getTeacherId).distinct().toList());

        var out = new ArrayList<TeacherIsland>(active.size());
        for (var q : active) {
            var teacher = teachers.get(q.getTeacherId());
            out.add(new TeacherIsland(q.getId(), displayName(teacher == null ? null : teacher.getDisplayName()),
                    teacher == null ? null : teacher.getPhotoUrl(),
                    q.getTitle(), kdate(q.getFromDate()), kdate(q.getToDate()), stopIdsOf(q).size(),
                    answered.getOrDefault(q.getId(), 0)));
        }
        return List.copyOf(out);
    }

    /** `GET /children/{id}/teacher-questions/{questionId}`: the stops, shaped like a play the app can run. */
    public TeacherQuestionPlay playFor(quest.server.children.Entities.ChildEntity child, String questionId, LocalDate today) {
        var row = visibleTo(child, questionId, today);
        var answered = answers.findByQuestionIdAndChildId(questionId, child.getId()).stream()
                .map(Entities.TeacherQuestionAnswerEntity::getStopId).toList();
        var teacher = users.findById(row.getTeacherId()).orElse(null);
        return new TeacherQuestionPlay(row.getId(), row.getTitle(),
                displayName(teacher == null ? null : teacher.getDisplayName()),
                teacher == null ? null : teacher.getPhotoUrl(),
                kdate(row.getFromDate()), kdate(row.getToDate()), 1, 0, "teacher", THEME,
                decode(row.getStopsJson()), answered);
    }

    /**
     * `POST /children/{id}/teacher-questions/{questionId}/answers`: a batch, idempotent on (question, child, stop)
     * like `POST /children/{id}/attempts` is on the attempt id. An answer naming a stop the question does not have
     * is refused rather than stored, so the results table can never grow a column nobody was asked.
     */
    @Transactional
    public int recordAnswers(quest.server.children.Entities.ChildEntity child, String questionId,
                             List<TeacherAnswerUpload> uploads, LocalDate today) {
        var row = visibleTo(child, questionId, today);
        var stopIds = new HashSet<>(stopIdsOf(row));
        int accepted = 0;
        for (var upload : uploads) {
            if (!stopIds.contains(upload.getStopId()))
                throw ApiException.badRequest("stop " + upload.getStopId() + " is not part of that question");
            if (answers.findOne(questionId, child.getId(), upload.getStopId()).isPresent()) continue;
            var answer = new Entities.TeacherQuestionAnswerEntity();
            answer.setId(UUID.randomUUID().toString());
            answer.setSchoolId(row.getSchoolId());
            answer.setQuestionId(questionId); answer.setChildId(child.getId()); answer.setStopId(upload.getStopId());
            answer.setAnswerJson(upload.getAnswerJson() == null ? "{}" : upload.getAnswerJson());
            answer.setCorrect(upload.getCorrect());
            answer.setStars(Math.max(0, Math.min(3, upload.getStars())));
            answer.setAnsweredAt(Instant.ofEpochMilli(upload.getAnsweredAt()));
            answers.save(answer);
            accepted++;
        }
        return accepted;
    }

    /** Every answer a child has given to any question — one statement, for her timeline (§6 screen 15). */
    List<Entities.TeacherQuestionAnswerEntity> answersOf(String childId) { return answers.findByChildIdOrderByAnsweredAtDesc(childId); }

    List<Entities.TeacherQuestionEntity> questionsById(java.util.Collection<String> ids) {
        return ids.isEmpty() ? List.of() : questions.findAllByIdIn(ids);
    }

    // ---------------------------------------------------------------- scope and validation

    /** A question the caller may change: hers as a TEACHER, any of her school otherwise. */
    private Entities.TeacherQuestionEntity mine(Principals.User caller, String id) {
        var row = readable(caller, id);
        if ("MANAGERIAL".equals(caller.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's questions but not change them.");
        return row;
    }

    /**
     * A question the caller may read. The lookup is a query, so the `school` filter has already hidden every other
     * school's row; a TEACHER is then narrowed to her own questions — another teacher's is 404, not 403, because
     * the id is not hers to confirm.
     */
    private Entities.TeacherQuestionEntity readable(Principals.User caller, String id) {
        var row = questions.findOneById(id).orElseThrow(() -> ApiException.notFound("question"));
        if (access.isTeacher(caller) && !caller.userId().equals(row.getTeacherId())) throw ApiException.notFound("question");
        return row;
    }

    /** The question behind a child's island: sent, in window, and addressed to a class of hers. */
    private Entities.TeacherQuestionEntity visibleTo(quest.server.children.Entities.ChildEntity child, String questionId, LocalDate today) {
        var row = questions.findOneById(questionId).orElseThrow(() -> ApiException.notFound("question"));
        if (!row.getSchoolId().equals(child.getSchoolId())) throw ApiException.notFound("question");
        if (row.getSentAt() == null) throw ApiException.notFound("question");
        if (today.isBefore(row.getFromDate()) || today.isAfter(row.getToDate())) throw ApiException.notFound("question");
        var hers = classes.findBySchoolIdAndCurriculumAndGrade(child.getSchoolId(), child.getCurriculum(), child.getGrade())
                .stream().map(ClassEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (classIdsOf(row).stream().noneMatch(hers::contains)) throw ApiException.notFound("question");
        return row;
    }

    /** The class ids the caller may send to, refusing any that is not hers before a row is written. */
    private List<String> ownedClassIds(Principals.User caller, List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        var allowed = access.ownedClasses(caller).stream().map(ClassEntity::getId).collect(java.util.stream.Collectors.toSet());
        var out = new LinkedHashSet<String>();
        for (String id : requested) {
            // The id is not echoed back. The caller sent it, so repeating it tells her nothing she did not know —
            // but it puts another school's class id into a response body, which is exactly what `IsolationTest`
            // sweeps for, and a refusal is a bad place to start making exceptions to that rule.
            if (!allowed.contains(id)) throw ApiException.forbidden("One of those classes is not one of yours.");
            out.add(id);
        }
        return List.copyOf(out);
    }

    /**
     * Decodes and validates the stops. The codec refuses an unknown field, so a payload the app could not read is a
     * 400 here rather than a crash there; {@link SchemaValidator} then applies the per-type rules in lenient mode —
     * the same rules a hand-written manual play is held to.
     */
    List<Stop> validated(JsonNode stops) {
        if (stops == null || stops.isNull()) return List.of();
        if (!stops.isArray()) throw ApiException.badRequest("stops must be a JSON array of stop objects");
        if (stops.size() > MAX_STOPS) throw ApiException.badRequest("at most " + MAX_STOPS + " questions per message");
        List<Stop> decoded;
        try {
            decoded = json.decodeShared(stops.toString(), BuiltinSerializersKt.ListSerializer(Stop.Companion.serializer()));
        } catch (Exception e) {
            throw ApiException.badRequest("stops are not valid: " + e.getMessage());
        }
        if (decoded.isEmpty()) return List.of();
        var result = SchemaValidator.INSTANCE.validate(new Play(1, 0, SourceKind.MIXED, THEME, decoded, null), 1, Set.of(), true);
        if (!result.isValid()) throw ApiException.badRequest("stops are not valid: " + String.join("; ", result.getErrors()));
        return decoded;
    }

    private static LocalDate[] window(String from, String to) {
        LocalDate start, end;
        try { start = LocalDate.parse(from); end = LocalDate.parse(to); }
        catch (Exception e) { throw ApiException.badRequest("from/to must be ISO dates (yyyy-MM-dd)"); }
        if (end.isBefore(start)) throw ApiException.badRequest("to must not be before from");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) throw ApiException.badRequest("the window must be at most " + MAX_WINDOW_DAYS + " days");
        return new LocalDate[] {start, end};
    }

    private static String title(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) throw ApiException.badRequest("Title must be 1–120 characters.");
        return trimmed;
    }

    /** Whose question it is: a TEACHER's own, or for an Admin the teacher of the first class she chose. */
    private String authorOf(Principals.User caller, List<String> classIds) {
        if (access.isTeacher(caller)) return caller.userId();
        for (String id : classIds) {
            var klass = classes.findOneById(id).orElse(null);
            if (klass != null && klass.getTeacherId() != null) return klass.getTeacherId();
        }
        throw ApiException.badRequest("Those classes have no teacher yet — assign one before sending questions from them.");
    }

    // ---------------------------------------------------------------- shaping

    private TeacherDto.TeacherQuestion summaryOf(Entities.TeacherQuestionEntity row) {
        var classesById = classesOf(row.getSchoolId());
        var roster = audience(row, classesById, children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(row.getSchoolId()));
        var name = users.findById(row.getTeacherId()).map(UserEntity::getDisplayName).orElse(null);
        return summary(row, name, answers.findByQuestionIdOrderByAnsweredAtAsc(row.getId()), roster);
    }

    private TeacherDto.TeacherQuestion summary(Entities.TeacherQuestionEntity row, String teacherName,
                                               List<Entities.TeacherQuestionAnswerEntity> rows,
                                               List<quest.server.children.Entities.ChildEntity> roster) {
        var answeredChildren = new HashSet<String>();
        for (var answer : rows) answeredChildren.add(answer.getChildId());
        return new TeacherDto.TeacherQuestion(row.getId(), row.getSchoolId(), row.getTeacherId(), teacherName,
                row.getTitle(), json.tree(row.getStopsJson()), classIdsOf(row),
                row.getFromDate().toString(), row.getToDate().toString(), row.getCreatedAt().toEpochMilli(),
                row.getSentAt() == null ? null : row.getSentAt().toEpochMilli(),
                roster.size(), answeredChildren.size(), accuracy(rows));
    }

    /**
     * The children a question is addressed to: those of the school whose (curriculum, grade) matches one of the
     * chosen classes — §2's rule, the one the map already uses. Any subject: a child does not belong to a subject.
     */
    /**
     * The children a question is put to: every child of the <em>courses</em> its classes belong to, so a question
     * sent to `1A` also reaches `1B`. The same shape as {@link AnnouncementService#forChild} and the one N2.3b
     * narrowed in `TeacherStudentService`; both are on the backlog together, because tightening them changes what a
     * child's map already offers.
     */
    private List<quest.server.children.Entities.ChildEntity> audience(Entities.TeacherQuestionEntity row,
                                                                      Map<String, ClassEntity> classesById,
                                                                      List<quest.server.children.Entities.ChildEntity> roster) {
        var courses = new HashSet<String>();
        for (String id : classIdsOf(row)) {
            var klass = classesById.get(id);
            if (klass != null) courses.add(klass.getCurriculum() + "/" + klass.getGrade());
        }
        if (courses.isEmpty()) return List.of();
        return roster.stream().filter(c -> courses.contains(c.getCurriculum() + "/" + c.getGrade())).toList();
    }

    /** The first of the question's classes this child sits in, for the results table's class column. */
    private static String classIdFor(quest.server.children.Entities.ChildEntity child, Map<String, ClassEntity> classesById, List<String> classIds) {
        for (String id : classIds) {
            var klass = classesById.get(id);
            if (klass != null && klass.getCurriculum().equals(child.getCurriculum()) && klass.getGrade() == child.getGrade()) return id;
        }
        return null;
    }

    private Map<String, ClassEntity> classesOf(String schoolId) {
        var out = new LinkedHashMap<String, ClassEntity>();
        for (var klass : classes.findBySchoolId(schoolId)) out.put(klass.getId(), klass);
        return out;
    }

    /** Answers are unique per (question, child, stop), so every stored row *is* a first try. */
    private static Double accuracy(List<Entities.TeacherQuestionAnswerEntity> rows) {
        if (rows.isEmpty()) return null;
        long correct = rows.stream().filter(Entities.TeacherQuestionAnswerEntity::isCorrect).count();
        return (double) correct / rows.size();
    }

    List<String> classIdsOf(Entities.TeacherQuestionEntity row) {
        return json.read(row.getClassIdsJson(), new TypeReference<List<String>>() {});
    }

    private List<String> stopIdsOf(Entities.TeacherQuestionEntity row) {
        return decode(row.getStopsJson()).stream().map(Stop::getId).toList();
    }

    List<Stop> decode(String stopsJson) {
        return json.decodeShared(stopsJson, BuiltinSerializersKt.ListSerializer(Stop.Companion.serializer()));
    }

    private String encode(List<Stop> stops) {
        return json.encodeShared(stops, BuiltinSerializersKt.ListSerializer(Stop.Companion.serializer()));
    }

    /** The teachers behind a page of questions, in one statement — the island needs both a name and a photo. */
    private Map<String, UserEntity> teachersById(List<String> ids) {
        var out = new HashMap<String, UserEntity>();
        if (ids.isEmpty()) return out;
        for (var user : users.findAllById(ids)) out.put(user.getId(), user);
        return out;
    }

    /** The island and the play always name somebody: a teacher who never set a display name is "Your teacher". */
    static String displayName(String value) { return value == null || value.isBlank() ? "Your teacher" : value; }

    static kotlinx.datetime.LocalDate kdate(LocalDate d) {
        return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }
}
