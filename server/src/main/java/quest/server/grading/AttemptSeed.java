package quest.server.grading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import quest.api.CacheKeys;
import quest.api.dto.Bilingual;
import quest.api.dto.Ingredient;
import quest.api.dto.Play;
import quest.api.dto.SourceKind;
import quest.api.dto.Stop;
import quest.api.dto.Theme;
import quest.api.dto.Tile;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.AttemptEntity;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.SkillRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TenantContext;

/**
 * `seed/attempts.csv` — a handful of children who have actually played something, so the dashboard's Results page
 * and gradebook have numbers to assert against in an e2e run rather than an empty grid (N4.1).
 *
 * <p><strong>The `full` profile only.</strong> `acceptance` is the owner's own environment: its children arrive when
 * he registers as a parent in the real app and its lessons are the ones he posts himself, so a fixture writing
 * attempts into it would put scores on the board that nobody played. The file is read only when
 * {@link quest.server.config.QuestProperties.Seed#profileOrFull()} is `full` and the school seed is on at all, which
 * is the same gate {@link quest.server.classes.SchoolSeed} runs behind. "After it" is now stated rather than hoped
 * for: {@link quest.server.classes.SeedOrder#ATTEMPTS} puts it last of the three, because until N4.3 the order was
 * whatever order component scanning found the beans in, and this loader fails outright on a class it cannot find.
 *
 * <p><strong>It brings its own lesson.</strong> Attempts hang off a lesson and a stop, and a QA environment has no
 * lessons until a teacher writes one, so the loader publishes one small homework per class named in the file:
 * {@link #LESSON_TITLE}, one level, two single-answer stops and one retell — which is exactly the shape §7 scores,
 * with an open stop left for the teacher to mark. The lesson id, the stop ids and the attempt ids are all derived
 * from the class id, so a second run finds its own rows and writes nothing.
 */
@Component
@Profile({"qa", "h2", "test"})
@org.springframework.core.annotation.Order(quest.server.classes.SeedOrder.ATTEMPTS)
public class AttemptSeed implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AttemptSeed.class);
    /** The lesson the file's attempts are about, one copy per class, published a week ago. */
    static final String LESSON_TITLE = "Counting to ten";
    private static final int STOPS = 3;

    private final QuestProperties props; private final ClassRepository classes; private final ChildRepository children;
    private final LessonRepository lessons; private final SkillRepository skills; private final LessonStore store;
    private final AttemptRepository attempts;

    public AttemptSeed(QuestProperties props, ClassRepository classes, ChildRepository children,
                       LessonRepository lessons, SkillRepository skills, LessonStore store, AttemptRepository attempts) {
        this.props = props; this.classes = classes; this.children = children; this.lessons = lessons;
        this.skills = skills; this.store = store; this.attempts = attempts;
    }

    @Override public void run(String... args) {
        var seed = props.seed();
        if (seed == null || !seed.school() || !"full".equals(seed.profileOrFull())) return;
        try { load(TenantContext.DEFAULT_SCHOOL); } catch (RuntimeException e) {
            log.error("attempt seed: skipped, the load goes on: {}", e.getMessage(), e);
        }
    }

    /** What one load wrote; a re-run answers zeroes. Visible to the tests, which call it with a school of their own. */
    public int load(String schoolId) {
        var rows = rows();
        if (rows.isEmpty()) return 0;
        var byName = new LinkedHashMap<String, ClassEntity>();
        for (var section : classes.findBySchoolId(schoolId)) byName.put(section.getName().toLowerCase(Locale.ROOT), section);

        int written = 0;
        var lessonByClass = new LinkedHashMap<String, LessonEntity>();
        for (var row : rows) {
            var section = byName.get(row.className().toLowerCase(Locale.ROOT));
            if (section == null) throw ApiException.badRequest("seed/attempts.csv: no class is named " + row.className());
            var lesson = lessonByClass.computeIfAbsent(section.getId(), id -> homework(schoolId, section));
            var child = children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId()).stream()
                    .filter(c -> c.getName().equalsIgnoreCase(row.childName())).findFirst().orElse(null);
            if (child == null) throw ApiException.badRequest("seed/attempts.csv: " + row.className() + " has no child called " + row.childName());
            String id = "seed-attempt:" + lesson.getId() + ":" + child.getId() + ":" + row.stop() + ":" + row.attemptNumber();
            if (attempts.existsById(id)) continue;
            var a = new AttemptEntity();
            a.setId(id); a.setChildId(child.getId()); a.setLessonId(lesson.getId()); a.setStopId(stopId(lesson.getId(), row.stop()));
            a.setLevel(1); a.setAnswerJson("{\"seed\":true}"); a.setCorrect(row.correct()); a.setAttemptNumber(row.attemptNumber());
            a.setMistakes(row.mistakes()); a.setStars(row.stars());
            a.setAnsweredAt(Instant.now().minus(6, ChronoUnit.DAYS).plus(row.stop(), ChronoUnit.MINUTES));
            attempts.save(a);
            written++;
        }
        log.info("attempt seed: {} lessons, {} new attempts", lessonByClass.size(), written);
        return written;
    }

    /** One published homework per class, created once and found by its id afterwards. */
    private LessonEntity homework(String schoolId, ClassEntity section) {
        String id = "seed-homework-" + section.getId();
        var existing = lessons.findById(id).orElse(null);
        if (existing != null) return existing;
        var lesson = new LessonEntity();
        lesson.setId(id); lesson.setSchoolId(schoolId); lesson.setClassId(section.getId());
        lesson.setCourseId(section.getCurriculum() + "/" + section.getGrade()); lesson.setSubject("math");
        lesson.setDate(LocalDate.now().minusDays(6)); lesson.setStatus("published"); lesson.setVersion(1);
        lesson.setTitle(LESSON_TITLE); lesson.setSource("manual"); lesson.setSourceHash("seed-" + id);
        lesson.setCreatedBy("seed"); lesson.setCreatedAt(Instant.now()); lesson.setUpdatedAt(Instant.now());
        lesson.setPublishedAt(Instant.now().minus(6, ChronoUnit.DAYS));
        lessons.save(lesson);

        var skill = new SkillEntity();
        skill.setId(id + ":skill-1"); skill.setLessonId(id); skill.setName("Counting to ten"); skill.setSubject("math");
        skill.setMethod("practice"); skill.setConfidence(1.0); skill.setConfirmed(true); skill.setPosition(0);
        skills.save(skill);

        store.savePlay(id, new Play(1, 0, SourceKind.MATH, new Theme("Pot", "Soup", "🍲", "Served!"),
                List.of(choice(stopId(id, 1), "How many carrots?"), choice(stopId(id, 2), "How many peas?"),
                        retell(stopId(id, 3))), null), CacheKeys.PROMPT_B_VERSION, 1);
        return lesson;
    }

    /** `<lesson>:s<n>`, so an attempt row written by a later run lands on the same stop as the first one. */
    static String stopId(String lessonId, int stop) { return lessonId + ":s" + stop; }

    private static Stop.Choice choice(String id, String question) {
        return new Stop.Choice(id, question, question, new Ingredient("🥕", "carrot"),
                new Bilingual("Count them together.", "عدّوها معًا."),
                "Count them first.", question,
                List.of(new Tile("a", "3", null, null), new Tile("b", "4", null, null)), "a", null, null);
    }

    /** The open stop §7 leaves unscored until the teacher marks it — the reason the seeded grid has a marking badge. */
    private static Stop.Retell retell(String id) {
        return new Stop.Retell(id, "Tell the story back", "Tell me what happened.", new Ingredient("🥔", "potato"),
                new Bilingual("Ask them to retell it.", "اطلبوا إعادة الحكاية."),
                "What happened first?", List.of(), "First we counted, then we cooked.", true, null, null);
    }

    // ---------------------------------------------------------------- the file

    record Row(String className, String childName, int stop, int attemptNumber, boolean correct, int mistakes, int stars) {}

    private static List<Row> rows() {
        String text;
        try (var in = new ClassPathResource("seed/attempts.csv").getInputStream()) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw ApiException.badRequest("seed/attempts.csv cannot be read: " + e.getMessage()); }
        return parse(text);
    }

    /** Line 1 is the header; a row with the wrong number of columns or an out-of-range stop stops the load. */
    static List<Row> parse(String text) {
        var out = new java.util.ArrayList<Row>();
        var lines = text.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            var cells = line.split(",", -1);
            if (cells.length != 7) throw ApiException.badRequest("seed/attempts.csv line " + (i + 1) + ": 7 columns expected, " + cells.length + " found");
            int stop = Integer.parseInt(cells[2].strip());
            if (stop < 1 || stop > STOPS) throw ApiException.badRequest("seed/attempts.csv line " + (i + 1) + ": stop must be 1-" + STOPS);
            out.add(new Row(cells[0].strip(), cells[1].strip(), stop, Integer.parseInt(cells[3].strip()),
                    Boolean.parseBoolean(cells[4].strip()), Integer.parseInt(cells[5].strip()), Integer.parseInt(cells[6].strip())));
        }
        return List.copyOf(out);
    }

    /** The lesson the file's attempts land on, for a caller that wants to read the numbers back. */
    public static String lessonIdOf(String classId) { return "seed-homework-" + classId; }
}
