package quest.server.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
import quest.server.children.Entities.AttemptEntity;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.content.StopRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * The fixture the P3.0 tests share: a whole school built row by row — teachers, classes, children, published and
 * failed lessons, and the attempts a Home and a usage report are computed from.
 *
 * <p>Everything it seeds is prefixed with the test's own key, and {@link #removeSeed} takes it out again: the suite
 * shares one H2 database, and a report that counted another class's leftovers would be a flaky test rather than a
 * failing one.
 */
abstract class DashboardTestSupport extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired LessonRepository lessons;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired AttemptRepository attempts;
    @Autowired PlayRepository plays;
    @Autowired StopRepository stops;
    @Autowired SkillRepository skills;
    @Autowired LessonStore store;
    @Autowired AdminJwtService jwt;

    /** The prefix every row this test seeds carries, so {@link #removeSeed} can find it again. */
    abstract String prefix();

    static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }

    /**
     * Noon UTC on a given day. Every figure here is bucketed by UTC day or ISO week, so an attempt seeded as
     * "three hours ago" lands in yesterday's bucket or today's depending on the hour the suite happens to run —
     * which is a flake that only appears at 04:00. Seed the day you mean, at a fixed point inside it.
     */
    static Instant noon(LocalDate day) { return day.atTime(12, 0).toInstant(ZoneOffset.UTC); }

    // ---------------------------------------------------------------- tokens

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
        return user(id, schoolId, email, role, "active", Instant.now());
    }

    UserEntity user(String id, String schoolId, String email, String role, String status, Instant createdAt) {
        return users.findById(id).orElseGet(() -> {
            var u = new UserEntity();
            u.setId(id); u.setSchoolId(schoolId); u.setEmail(email); u.setPasswordHash("x"); u.setRole(role);
            u.setStatus(status); u.setDisplayName(null); u.setCreatedAt(createdAt); u.setUpdatedAt(createdAt);
            return users.save(u);
        });
    }

    void teacherProfile(String userId, String subjectsJson, String curriculum, String gradesJson) {
        var t = new TeacherEntity();
        t.setUserId(userId); t.setSubjectsJson(subjectsJson); t.setCurriculum(curriculum); t.setGradesJson(gradesJson);
        t.setUpdatedAt(Instant.now());
        teacherProfiles.save(t);
    }

    /**
     * The (grade, subject) pairs a growing fixture may use, in order. `courses` only has grades 1–3, and `classes` is
     * unique on (school, curriculum, grade, subject, teacher) — so a test that adds "one more class for the same
     * teacher" has to walk distinct pairs rather than counting grades upwards.
     */
    static final List<int[]> COURSE_SLOTS = List.of(new int[] {1, 0}, new int[] {1, 1}, new int[] {2, 0},
            new int[] {2, 1}, new int[] {3, 0}, new int[] {3, 1});

    static int slotGrade(int index) { return COURSE_SLOTS.get(index % COURSE_SLOTS.size())[0]; }

    static String slotSubject(int index) { return COURSE_SLOTS.get(index % COURSE_SLOTS.size())[1] == 0 ? "math" : "english"; }

    /** A section (V7): named, with a join code, and with the teaching assignment that makes it the teacher's. */
    ClassEntity klass(String id, String schoolId, String curriculum, int grade, String subject, String teacherId) {
        return quest.server.ClassFixtures.section(classes, assignments, id, schoolId, curriculum, grade, subject, teacherId);
    }

    LessonEntity lesson(String id, String schoolId, String classId, String curriculum, int grade, String subject,
                        LocalDate date, String status, Instant publishedAt, long tokenUsage) {
        var l = lessons.findById(id).orElseGet(LessonEntity::new);
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(classId); l.setCourseId(curriculum + "/" + grade);
        l.setSubject(subject); l.setDate(date); l.setStatus(status); l.setVersion(1); l.setTitle("Lesson " + id);
        l.setSource("pdf"); l.setTokenUsage(tokenUsage); l.setPublishedAt(publishedAt);
        if (l.getCreatedAt() == null) l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        return lessons.save(l);
    }

    /**
     * A published lesson with one play, one single-answer stop and one confirmed skill — the least a skill band can
     * be computed from. The play goes through {@link LessonStore} rather than hand-written JSON so the fixture can
     * never drift from the real serial shape of a `Stop`.
     */
    LessonEntity lessonWithSkill(String id, String schoolId, String classId, String curriculum, int grade, String subject,
                                 LocalDate date, String skillName) {
        var l = lesson(id, schoolId, classId, curriculum, grade, subject, date, "published", Instant.now(), 1000);
        var tip = new Bilingual("Ask them to count again.", "Ask them to count again.");
        var stop = new Stop.Choice(stopId(id), "Pick one", "Which one is it?", new Ingredient("🥕", "carrot"), tip,
                "Count them first.", "Which is bigger?",
                List.of(new Tile("a", "A", null, null), new Tile("b", "B", null, null)), "a", null);
        store.savePlay(id, new Play(1, 0, SourceKind.MATH,
                new Theme("Pot", "Soup", "🍲", "Served!"), List.of(stop), null), "v1", 1);
        var skill = new SkillEntity();
        skill.setId(id + ":skill-1"); skill.setLessonId(id); skill.setName(skillName); skill.setSubject(subject);
        skill.setMethod("practice"); skill.setConfidence(1.0); skill.setConfirmed(true); skill.setPosition(0);
        skills.save(skill);
        return l;
    }

    /** The single-answer stop {@link #lessonWithSkill} puts in the lesson, which the attempts below answer. */
    static String stopId(String lessonId) { return lessonId + ":stop-1"; }

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

    /** Puts back what this test seeded: the suite shares one database and reports count whatever is in it. */
    void removeSeed() {
        String p = prefix();
        attempts.deleteAll(attempts.findAll().stream().filter(a -> a.getId().startsWith(p) || a.getLessonId().startsWith(p)).toList());
        skills.deleteAll(skills.findAll().stream().filter(s -> s.getLessonId().startsWith(p)).toList());
        stops.deleteAll(stops.findAll().stream().filter(s -> s.getLessonId().startsWith(p)).toList());
        plays.deleteAll(plays.findAll().stream().filter(x -> x.getLessonId().startsWith(p)).toList());
        lessons.deleteAll(lessons.findAll().stream().filter(l -> l.getId().startsWith(p)).toList());
    }
}
