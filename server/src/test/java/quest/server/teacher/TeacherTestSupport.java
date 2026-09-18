package quest.server.teacher;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.api.dto.Bilingual;
import quest.api.dto.Ingredient;
import quest.api.dto.Play;
import quest.api.dto.SourceKind;
import quest.api.dto.Stop;
import quest.api.dto.Theme;
import quest.api.dto.Tile;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.AttemptEntity;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.content.StopRepository;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * The fixture the P4.0 tests share: two schools, a teacher in each (plus a second teacher in the first), the
 * classes they own, and the children who sit in them.
 *
 * <p>Everything a test seeds is prefixed with its own key and {@link #removeSeed} takes it out again — the suite
 * shares one H2 database, and a results table that counted another test's children would be a flaky test rather
 * than a failing one.
 */
abstract class TeacherTestSupport extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired LessonRepository lessons;
    @Autowired PlayRepository plays;
    @Autowired StopRepository stops;
    @Autowired SkillRepository skills;
    @Autowired AttemptRepository attempts;
    @Autowired ChildRepository childRows;
    @Autowired TeacherQuestionRepository questionRows;
    @Autowired TeacherQuestionAnswerRepository answerRows;
    @Autowired AnnouncementRepository announcementRows;
    @Autowired LessonStore store;
    @Autowired AdminJwtService jwt;

    /** The prefix every row this test seeds carries, so {@link #removeSeed} can find it again. */
    abstract String prefix();

    // ---------------------------------------------------------------- tokens and requests

    String token(String userId, String role, String schoolId) {
        return jwt.issue(userId, userId + "@seed.test", role, schoolId).token();
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        var request = as(builder, token);
        return schoolId == null ? request : request.header(TenantContext.HEADER, schoolId);
    }

    /** Switches a flag for one school through the real Admin route, cache invalidation included. */
    void setFlag(String adminToken, String schoolId, String key, boolean enabled) throws Exception {
        mvc.perform(as(put("/admin/schools/" + schoolId + "/flags/" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), adminToken)).andExpect(status().isOk());
    }

    /** Both P4.0 features on for a school, which is how most of these tests want to start. */
    void enableTeacherFeatures(String adminToken, String schoolId) throws Exception {
        setFlag(adminToken, schoolId, FlagKeys.TEACHER_QUESTIONS, true);
        setFlag(adminToken, schoolId, FlagKeys.ANNOUNCEMENTS, true);
    }

    // ---------------------------------------------------------------- rows

    SchoolEntity school(String id, String name, String code) {
        return schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    UserEntity user(String id, String schoolId, String email, String role) {
        return users.findById(id).orElseGet(() -> {
            var u = new UserEntity();
            u.setId(id); u.setSchoolId(schoolId); u.setEmail(email); u.setPasswordHash("x"); u.setRole(role);
            u.setStatus("active"); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            return users.save(u);
        });
    }

    /** A TEACHER account with the display name and photo the island shows, and a filled-in teaching profile. */
    UserEntity teacher(String id, String schoolId, String email, String displayName, String subjectsJson,
                       String curriculum, String gradesJson) {
        var u = user(id, schoolId, email, "TEACHER");
        u.setDisplayName(displayName);
        u.setPhotoUrl("https://cdn.example.test/" + id + ".png");
        users.save(u);
        var t = teacherProfiles.findById(id).orElseGet(TeacherEntity::new);
        t.setUserId(id); t.setSubjectsJson(subjectsJson); t.setCurriculum(curriculum); t.setGradesJson(gradesJson);
        t.setUpdatedAt(Instant.now());
        teacherProfiles.save(t);
        return u;
    }

    /** A section (V7): named, with a join code, and with the teaching assignment that makes it the teacher's. */
    ClassEntity klass(String id, String schoolId, String curriculum, int grade, String subject, String teacherId) {
        return quest.server.ClassFixtures.section(classes, assignments, id, schoolId, curriculum, grade, subject, teacherId);
    }

    LessonEntity lesson(String id, String schoolId, String classId, String curriculum, int grade, String subject,
                        LocalDate date, String status) {
        var l = lessons.findById(id).orElseGet(LessonEntity::new);
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(classId); l.setCourseId(curriculum + "/" + grade);
        l.setSubject(subject); l.setDate(date); l.setStatus(status); l.setVersion(1); l.setTitle("Lesson " + id);
        l.setSource("pdf"); l.setPublishedAt("published".equals(status) ? Instant.now() : null);
        if (l.getCreatedAt() == null) l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        return lessons.save(l);
    }

    /**
     * A published lesson with one play, one single-answer stop and one confirmed skill — the least a skill band can
     * be computed from. The play goes through {@link LessonStore} rather than hand-written JSON so the fixture can
     * never drift from the real serial shape of a `Stop`.
     */
    LessonEntity lessonWithSkill(String id, String schoolId, String classId, String curriculum, int grade,
                                 String subject, LocalDate date, String skillName) {
        var l = lesson(id, schoolId, classId, curriculum, grade, subject, date, "published");
        store.savePlay(id, new Play(1, 0, SourceKind.MATH, new Theme("Pot", "Soup", "🍲", "Served!"),
                List.of(choice(stopId(id), "Pick one")), null), "v1", 1);
        var skill = new SkillEntity();
        skill.setId(id + ":skill-1"); skill.setLessonId(id); skill.setName(skillName); skill.setSubject(subject);
        skill.setMethod("practice"); skill.setConfidence(1.0); skill.setConfirmed(true); skill.setPosition(0);
        skills.save(skill);
        return l;
    }

    /** The single-answer stop {@link #lessonWithSkill} puts in the lesson, which the attempts below answer. */
    static String stopId(String lessonId) { return lessonId + ":stop-1"; }

    /**
     * A lesson `AdminLessonService.publish` will accept: three levels, the Again variant, a parent panel and one
     * confirmed skill — what `LessonStore.assemble` insists on. Its ids carry the lesson's own prefix, the way the
     * pipeline writes them, so a copy of it has something real to rewrite.
     */
    LessonEntity readyLesson(String id, String schoolId, String classId, String curriculum, int grade, String subject,
                             LocalDate date) {
        var l = lesson(id, schoolId, classId, curriculum, grade, subject, date, "review");
        l.setVersion(0); l.setPublishedAt(null); l.setSourceHash("hash-" + id); lessons.save(l);
        for (int level = 1; level <= 3; level++) store.savePlay(id, play(id, level, 0), "v1", level);
        store.savePlay(id, play(id, 1, 1), "v1", 9);
        store.savePanel(id, new quest.api.dto.ParentPanel(
                new quest.api.dto.BilingualList(List.of("Practise together."), List.of("تدرّبوا معًا.")),
                List.of(new Bilingual("Count them out loud.", "عدّوا بصوت عالٍ.")),
                List.of(new Bilingual("Try the next ten.", "جرّبوا العشرة التالية.")),
                List.of(new quest.api.dto.StopTip(prefixed(id, 1, 0), "Ask them to count again.", "اطلبوا العدّ مجددًا.")),
                List.of()));
        var skill = new SkillEntity();
        skill.setId(quest.server.analysis.StopIds.prefix8(id) + ":skill-1"); skill.setLessonId(id); skill.setName("Counting");
        skill.setSubject(subject); skill.setMethod("practice"); skill.setConfidence(1.0); skill.setConfirmed(true);
        skill.setPosition(0);
        skills.save(skill);
        return l;
    }

    private static Play play(String lessonId, int level, int variant) {
        return new Play(level, variant, SourceKind.MATH, new Theme("Pot", "Soup", "🍲", "Served!"),
                List.of(choice(prefixed(lessonId, level, variant), "Pick one")), null);
    }

    /** The lesson-unique stop id the pipeline would write: `<lesson8>:<level>:<variant>:s1`. */
    static String prefixed(String lessonId, int level, int variant) {
        return quest.server.analysis.StopIds.prefix(lessonId, level, variant) + "s1";
    }

    /** A section with the name a school gives it ("1A"), for the tests where two sections share one grade. */
    ClassEntity klass(String id, String schoolId, String curriculum, int grade, String subject, String teacherId, String name) {
        var section = klass(id, schoolId, curriculum, grade, subject, teacherId);
        section.setName(name);
        return classes.save(section);
    }

    /**
     * A child on one section's roster — `children.class_id`, which is what every "this class's children" query
     * reads. A child created with a school code alone sits in no section and belongs to no teacher (N2.3b).
     */
    String child(String name, String schoolCode, ClassEntity section) throws Exception {
        String id = child(name, schoolCode, section.getCurriculum(), section.getGrade());
        childRows.findById(id).ifPresent(c -> { c.setClassId(section.getId()); childRows.save(c); });
        return id;
    }

    /** A child of a school, created through the parent API so its parent row and foreign keys are real. */
    String child(String name, String schoolCode, String curriculum, int grade) throws Exception {
        return parentPost("/children", "{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + curriculum
                + "\",\"grade\":" + grade + ",\"schoolCode\":\"" + schoolCode + "\"}").get("id").asText();
    }

    void attempt(String childId, String lessonId, String stopId, boolean correct, Instant at) {
        var a = new AttemptEntity();
        a.setId(prefix() + "-" + UUID.randomUUID()); a.setChildId(childId); a.setStopId(stopId); a.setLessonId(lessonId);
        a.setLevel(1); a.setAnswerJson("{}"); a.setCorrect(correct); a.setAttemptNumber(1); a.setMistakes(correct ? 0 : 1);
        a.setStars(correct ? 3 : 1); a.setAnsweredAt(at);
        attempts.save(a);
    }

    // ---------------------------------------------------------------- stops

    /** A valid single-answer stop, built from the real DTOs so it always matches `Play.schema.json`. */
    static Stop.Choice choice(String id, String title) {
        return new Stop.Choice(id, title, "Which one is it?", new Ingredient("🥕", "carrot"),
                new Bilingual("Ask them to count again.", "Ask them to count again."),
                "Count them first.", "Which is bigger?",
                List.of(new Tile("a", "A", null, null), new Tile("b", "B", null, null)), "a", null);
    }

    /** The JSON a teacher's client posts as `stops`: the shared codec's encoding of a list of stops. */
    String stopsJson(Stop... values) {
        return json().encodeShared(List.of(values),
                kotlinx.serialization.builtins.BuiltinSerializersKt.ListSerializer(Stop.Companion.serializer()));
    }

    @Autowired quest.server.config.Json jsonCodec;

    quest.server.config.Json json() { return jsonCodec; }

    // ---------------------------------------------------------------- cleanup

    /** Puts back what this test seeded: the suite shares one database and every report counts whatever is in it. */
    void removeSeed() {
        String p = prefix();
        // Children are soft-deleted rather than removed: `attempts`, `stop_completions` and the media rows all
        // point at `children.id`, and every query in this package already filters on `deleted_at is null`. Without
        // this a later test in the same class would count an earlier one's children into `totalChildren`.
        childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith(p) && c.getDeletedAt() == null)
                .forEach(c -> { c.setDeletedAt(Instant.now()); childRows.save(c); });
        // Questions, answers and announcements are keyed by UUID, so the test's prefix is matched on `school_id`
        // (which is why the answers carry one). Answers go first: they have a foreign key into `teacher_questions`.
        answerRows.deleteAll(answerRows.findAll().stream().filter(a -> a.getSchoolId().startsWith(p)).toList());
        questionRows.deleteAll(questionRows.findAll().stream().filter(q -> q.getSchoolId().startsWith(p)).toList());
        announcementRows.deleteAll(announcementRows.findAll().stream().filter(a -> a.getSchoolId().startsWith(p)).toList());
        // A lesson made through the API carries a UUID, not the test's prefix, so the set is "this test's schools'
        // lessons" and everything that hangs off one is matched by id against that set rather than by prefix.
        var mine = lessons.findAll().stream().filter(l -> l.getId().startsWith(p) || l.getSchoolId().startsWith(p)).toList();
        var ids = mine.stream().map(quest.server.content.Entities.LessonEntity::getId).collect(java.util.stream.Collectors.toSet());
        attempts.deleteAll(attempts.findAll().stream().filter(a -> a.getId().startsWith(p) || ids.contains(a.getLessonId())).toList());
        skills.deleteAll(skills.findAll().stream().filter(s -> ids.contains(s.getLessonId())).toList());
        stops.deleteAll(stops.findAll().stream().filter(s -> ids.contains(s.getLessonId())).toList());
        plays.deleteAll(plays.findAll().stream().filter(x -> ids.contains(x.getLessonId())).toList());
        lessons.deleteAll(mine);
    }
}
