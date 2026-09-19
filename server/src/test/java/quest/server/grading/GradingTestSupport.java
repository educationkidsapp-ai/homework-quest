package quest.server.grading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * The fixture the N4.1 tests share: a school, a teacher, her sections, the children on them, and a published lesson
 * whose stops are exactly the ones {@link ScoringTest} computes by hand — two single-answer stops and one retell.
 *
 * <p>Modelled on `TeacherTestSupport` and separate from it for the same reason that one exists: the suite shares one
 * H2 database, so everything a test seeds carries its own prefix and {@link #removeSeed} takes it out again.
 */
abstract class GradingTestSupport extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired LessonRepository lessons;
    @Autowired PlayRepository plays;
    @Autowired StopRepositoryHolder stopsHolder;
    @Autowired SkillRepository skills;
    @Autowired AttemptRepository attempts;
    @Autowired ChildRepository childRows;
    @Autowired TeacherMarkRepository markRows;
    @Autowired LessonStore store;
    @Autowired AdminJwtService jwt;

    /** Spring needs a bean for `StopRepository`; holding it indirectly keeps the import list of this class short. */
    @org.springframework.stereotype.Component
    static class StopRepositoryHolder {
        final quest.server.content.StopRepository stops;
        StopRepositoryHolder(quest.server.content.StopRepository stops) { this.stops = stops; }
    }

    abstract String prefix();

    // ---------------------------------------------------------------- tokens

    String token(String userId, String role, String schoolId) {
        return jwt.issue(userId, userId + "@seed.test", role, schoolId).token();
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    /** Switches a flag for one school through the real Admin route, cache invalidation included. */
    void setFlag(String adminToken, String schoolId, String key, boolean enabled) throws Exception {
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/admin/schools/" + schoolId + "/flags/" + key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":" + enabled + "}"), adminToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** Both N4.1 features on: the routes are 404 while `gradebook` is off, and marking needs its own key too. */
    void enableGrading(String adminToken, String schoolId) throws Exception {
        setFlag(adminToken, schoolId, quest.server.flags.FlagKeys.GRADEBOOK, true);
        setFlag(adminToken, schoolId, quest.server.flags.FlagKeys.OPEN_STOP_MARKING, true);
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

    UserEntity teacher(String id, String schoolId, String displayName) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@seed.test"); u.setPasswordHash("x"); u.setRole("TEACHER");
        u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
        var t = teacherProfiles.findById(id).orElseGet(TeacherEntity::new);
        t.setUserId(id); t.setSubjectsJson("[\"math\"]"); t.setCurriculum("british"); t.setGradesJson("[1]");
        t.setUpdatedAt(Instant.now());
        teacherProfiles.save(t);
        return u;
    }

    ClassEntity klass(String id, String schoolId, String teacherId, String name) {
        var section = quest.server.ClassFixtures.section(classes, assignments, id, schoolId, "british", 1, "math", teacherId);
        section.setName(name);
        return classes.save(section);
    }

    /** A child on a section's roster, created through the parent API so her parent row and foreign keys are real. */
    String child(String name, String schoolCode, ClassEntity section) throws Exception {
        String id = parentPost("/children", "{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\""
                + section.getCurriculum() + "\",\"grade\":" + section.getGrade() + ",\"schoolCode\":\"" + schoolCode + "\"}")
                .get("id").asText();
        childRows.findById(id).ifPresent(c -> { c.setClassId(section.getId()); childRows.save(c); });
        return id;
    }

    /**
     * A published lesson with one level and {@link ScoringTest}'s three stops: two single-answer and one retell, so
     * the score of a child who plays it is the one computed by hand there.
     */
    LessonEntity lesson(String id, String schoolId, ClassEntity section, LocalDate date) {
        var l = lessons.findById(id).orElseGet(LessonEntity::new);
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(section.getId());
        l.setCourseId(section.getCurriculum() + "/" + section.getGrade()); l.setSubject("math"); l.setDate(date);
        l.setStatus("published"); l.setVersion(1); l.setTitle("Lesson " + id); l.setSource("pdf");
        l.setPublishedAt(Instant.now());
        if (l.getCreatedAt() == null) l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        lessons.save(l);
        if (plays.findByLessonIdOrderByLevelAscVariantAsc(id).isEmpty()) {
            store.savePlay(id, new Play(1, 0, SourceKind.MATH, new Theme("Pot", "Soup", "S", "Served!"),
                    List.of(choice(stopId(id, 1)), choice(stopId(id, 2)), retell(stopId(id, 3))), null), "v1", 1);
            var skill = new SkillEntity();
            skill.setId(id + ":skill-1"); skill.setLessonId(id); skill.setName("Counting"); skill.setSubject("math");
            skill.setMethod("practice"); skill.setConfidence(1.0); skill.setConfirmed(true); skill.setPosition(0);
            skills.save(skill);
        }
        return l;
    }

    static String stopId(String lessonId, int stop) { return lessonId + ":s" + stop; }

    void attempt(String childId, String lessonId, int stop, int number, boolean correct, int stars) {
        var a = new AttemptEntity();
        a.setId(prefix() + UUID.randomUUID()); a.setChildId(childId); a.setLessonId(lessonId);
        a.setStopId(stopId(lessonId, stop)); a.setLevel(1); a.setAnswerJson("{}"); a.setCorrect(correct);
        a.setAttemptNumber(number); a.setMistakes(correct ? 0 : 1); a.setStars(stars);
        a.setAnsweredAt(Instant.now().minus(2, ChronoUnit.HOURS).plus(stop, ChronoUnit.MINUTES));
        attempts.save(a);
    }

    /** {@link ScoringTest}'s sample, played for real: 3 stars, wrong-then-right, and an unmarked retell. */
    void playTheSample(String childId, String lessonId) {
        attempt(childId, lessonId, 1, 1, true, 3);
        attempt(childId, lessonId, 2, 1, false, 1);
        attempt(childId, lessonId, 2, 2, true, 2);
        attempt(childId, lessonId, 3, 1, true, 3);
    }

    // ---------------------------------------------------------------- stops

    private static Stop.Choice choice(String id) {
        return new Stop.Choice(id, id, "Which one?", new Ingredient("C", "carrot"),
                new Bilingual("Count together.", "Count together."), "Count first.", "Which is bigger?",
                List.of(new Tile("a", "A", null, null), new Tile("b", "B", null, null)), "a", null, null);
    }

    private static Stop.Retell retell(String id) {
        return new Stop.Retell(id, id, "Tell it back", new Ingredient("P", "potato"),
                new Bilingual("Ask them to retell.", "Ask them to retell."), "What happened?", List.of(),
                "We counted.", true, null, null);
    }

    // ---------------------------------------------------------------- cleanup

    void removeSeed() {
        String p = prefix();
        childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith(p) && c.getDeletedAt() == null)
                .forEach(c -> { c.setDeletedAt(Instant.now()); childRows.save(c); });
        var mine = lessons.findAll().stream().filter(l -> l.getId().startsWith(p) || l.getSchoolId().startsWith(p)).toList();
        var ids = mine.stream().map(LessonEntity::getId).collect(java.util.stream.Collectors.toSet());
        markRows.deleteAll(markRows.findAll().stream().filter(m -> ids.contains(m.getLessonId())).toList());
        attempts.deleteAll(attempts.findAll().stream().filter(a -> a.getId().startsWith(p) || ids.contains(a.getLessonId())).toList());
        skills.deleteAll(skills.findAll().stream().filter(s -> ids.contains(s.getLessonId())).toList());
        stopsHolder.stops.deleteAll(stopsHolder.stops.findAll().stream().filter(s -> ids.contains(s.getLessonId())).toList());
        plays.deleteAll(plays.findAll().stream().filter(x -> ids.contains(x.getLessonId())).toList());
        lessons.deleteAll(mine);
    }

    String schoolOf(String prefix) { return prefix + "school"; }

    static String defaultSchool() { return TenantContext.DEFAULT_SCHOOL; }
}
