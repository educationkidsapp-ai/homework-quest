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
 *
 * <p>Public since N4.3: the exam tests in `quest.server.exams` need the same school, teacher, section, roster and
 * scorable lesson, and a second copy of this fixture is a second set of numbers to keep in step with
 * {@link ScoringTest}'s hand-computed sample.
 */
public abstract class GradingTestSupport extends ApiTestSupport {
    @Autowired public SchoolRepository schools;
    @Autowired public ClassRepository classes;
    @Autowired public quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired public UserRepository users;
    @Autowired public TeacherRepository teacherProfiles;
    @Autowired public LessonRepository lessons;
    @Autowired public PlayRepository plays;
    @Autowired public StopRepositoryHolder stopsHolder;
    @Autowired public SkillRepository skills;
    @Autowired public AttemptRepository attempts;
    @Autowired public ChildRepository childRows;
    @Autowired public TeacherMarkRepository markRows;
    @Autowired public quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired public quest.server.flags.FeatureFlags featureFlags;
    @Autowired public LessonStore store;
    @Autowired public AdminJwtService jwt;

    /** Spring needs a bean for `StopRepository`; holding it indirectly keeps the import list of this class short. */
    @org.springframework.stereotype.Component
    public static class StopRepositoryHolder {
        public final quest.server.content.StopRepository stops;
        public StopRepositoryHolder(quest.server.content.StopRepository stops) { this.stops = stops; }
    }

    public abstract String prefix();

    // ---------------------------------------------------------------- tokens

    public String token(String userId, String role, String schoolId) {
        return jwt.issue(userId, userId + "@seed.test", role, schoolId).token();
    }

    public org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    /**
     * Switches a flag for one school by writing the override row and invalidating the cache — the two things
     * `FlagService.set` does besides recording the flip.
     *
     * <p><strong>Deliberately not the Admin route.</strong> That route also writes a `flag_audit` row, the audit log
     * is global to the suite's one H2 database, and `FlagAdminTest` asserts that its own column action adds exactly
     * one row to a page capped at `FlagService.MAX_AUDIT_LIMIT` (200). A fixture that flips flags to set itself up
     * is not a flip anybody audits, and pushing that page over its limit fails a test in another package that has
     * nothing to do with this one.
     */
    public void setFlag(String adminToken, String schoolId, String key, boolean enabled) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(schoolId);
    }

    /**
     * Both N4.1 features on: the routes are 404 while `gradebook` is off, and marking needs its own key too.
     *
     */
    public void enableGrading(String adminToken, String schoolId) {
        setFlag(adminToken, schoolId, quest.server.flags.FlagKeys.GRADEBOOK, true);
        setFlag(adminToken, schoolId, quest.server.flags.FlagKeys.OPEN_STOP_MARKING, true);
    }


    // ---------------------------------------------------------------- rows

    public SchoolEntity school(String id, String name, String code) {
        return schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    public UserEntity teacher(String id, String schoolId, String displayName) {
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

    public ClassEntity klass(String id, String schoolId, String teacherId, String name) {
        var section = quest.server.ClassFixtures.section(classes, assignments, id, schoolId, "british", 1, "math", teacherId);
        section.setName(name);
        return classes.save(section);
    }

    /** A child on a section's roster, created through the parent API so her parent row and foreign keys are real. */
    public String child(String name, String schoolCode, ClassEntity section) throws Exception {
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
    public LessonEntity lesson(String id, String schoolId, ClassEntity section, LocalDate date) {
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

    public static String stopId(String lessonId, int stop) { return lessonId + ":s" + stop; }

    /**
     * A lesson `POST /teacher/lessons/{id}/publish` will accept: three levels, the "Again" variant, a parent panel
     * and one confirmed skill — what `LessonStore.assemble` insists on — left in `review`.
     */
    public LessonEntity readyToPublish(String id, String schoolId, ClassEntity section, LocalDate date, String type) {
        var l = lesson(id, schoolId, section, date);
        l.setStatus("review"); l.setVersion(0); l.setPublishedAt(null); l.setType(type);
        l.setSourceHash("hash-" + id);
        lessons.save(l);
        for (int level = 2; level <= 3; level++) store.savePlay(id, levelPlay(id, level, 0), "v1", level);
        store.savePlay(id, levelPlay(id, 1, 1), "v1", 9);
        store.savePanel(id, new quest.api.dto.ParentPanel(
                new quest.api.dto.BilingualList(List.of("Practise together."), List.of("تدرّبوا معًا.")),
                List.of(new Bilingual("Count them out loud.", "عدّوا بصوت عالٍ.")),
                List.of(new Bilingual("Try the next ten.", "جرّبوا العشرة التالية.")),
                List.of(new quest.api.dto.StopTip(stopId(id, 1), "Ask them to count again.", "اطلبوا العدّ مجددًا.")),
                List.of()));
        return l;
    }

    private static Play levelPlay(String lessonId, int level, int variant) {
        return new Play(level, variant, SourceKind.MATH, new Theme("Pot", "Soup", "S", "Served!"),
                List.of(choice(lessonId + ":L" + level + "v" + variant + ":s1")), null);
    }

    public void attempt(String childId, String lessonId, int stop, int number, boolean correct, int stars) {
        attemptStop(childId, lessonId, stopId(lessonId, stop), stop, number, correct, stars);
    }

    /** The same, on a stop named outright — a level 2 or 3 stop of {@link #readyToPublish}, say. */
    public void attemptStop(String childId, String lessonId, String stopId, int order, int number, boolean correct, int stars) {
        var a = new AttemptEntity();
        a.setId(prefix() + UUID.randomUUID()); a.setChildId(childId); a.setLessonId(lessonId);
        a.setStopId(stopId); a.setLevel(1); a.setAnswerJson("{}"); a.setCorrect(correct);
        a.setAttemptNumber(number); a.setMistakes(correct ? 0 : 1); a.setStars(stars);
        a.setAnsweredAt(Instant.now().minus(2, ChronoUnit.HOURS).plus(order, ChronoUnit.MINUTES));
        attempts.save(a);
    }

    /** {@link ScoringTest}'s sample, played for real: 3 stars, wrong-then-right, and an unmarked retell. */
    public void playTheSample(String childId, String lessonId) {
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

    public void removeSeed() {
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

    public String schoolOf(String prefix) { return prefix + "school"; }

    public static String defaultSchool() { return TenantContext.DEFAULT_SCHOOL; }
}
