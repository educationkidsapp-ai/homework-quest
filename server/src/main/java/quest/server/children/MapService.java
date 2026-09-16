package quest.server.children;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import quest.api.dto.Course;
import quest.api.dto.LessonCompletionInfo;
import quest.api.dto.MapResponse;
import quest.api.dto.PublishedLessonSummary;
import quest.api.dto.Subject;
import quest.api.map.MapAssembler;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.SkillRepository;
import quest.server.content.StopRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.teacher.TeacherQuestionService;

/**
 * `GET /children/{id}/map` — the shared §7 assembler fed from the database. The lessons come from the child's school
 * (§2: every published lesson of every Class of her school with her curriculum and grade, any subject, any teacher),
 * never from a global course.
 *
 * <p>Fixed query count: classes (1) + lessons (1) + Level 1 stop counts (1) + confirmed skills (1) + completions (1)
 * + parent unlocks (1), plus what `ProgressService` needs for the review islands — the published lessons are looked
 * up once and handed to it rather than fetched again.
 *
 * <p>P4.0 adds the "From your teacher" islands (§6 screen 14) here rather than inside the assembler: `MapAssembler`
 * is shared with the app and knows only the §7 rule, while a teacher's question is a server-side row behind a
 * feature flag. They are assembled last and attached to the response, so the islands the rule produces are exactly
 * what they were. While `teacherQuestions` is off for the child's school the field is absent altogether — the same
 * 404-shaped silence the routes give — and it costs no query at all.
 */
@Service
public class MapService {
    private final SchoolLessons schoolLessons; private final StopRepository stops; private final SkillRepository skills;
    private final LessonCompletionRepository completions; private final ParentUnlockRepository unlocks; private final ProgressService progress;
    private final TeacherQuestionService teacherQuestions; private final FeatureFlags flags;

    public MapService(SchoolLessons schoolLessons, StopRepository stops, SkillRepository skills, LessonCompletionRepository completions,
                      ParentUnlockRepository unlocks, ProgressService progress, TeacherQuestionService teacherQuestions, FeatureFlags flags) {
        this.schoolLessons = schoolLessons; this.stops = stops; this.skills = skills; this.completions = completions;
        this.unlocks = unlocks; this.progress = progress; this.teacherQuestions = teacherQuestions; this.flags = flags;
    }

    public MapResponse map(Entities.ChildEntity child, LocalDate from, LocalDate to, LocalDate today) {
        var course = Course.Companion.parse(child.courseId());
        var lessons = schoolLessons.publishedFor(child);
        var lessonIds = lessons.stream().map(quest.server.content.Entities.LessonEntity::getId).toList();
        Map<String, Integer> stopsPerPlay = new HashMap<>();
        Map<String, List<String>> skillIds = new HashMap<>();
        if (!lessonIds.isEmpty()) {
            for (Object[] row : stops.countLevelOneStops(lessonIds)) stopsPerPlay.put((String) row[0], ((Number) row[1]).intValue());
            for (SkillEntity s : skills.findByLessonIdInAndConfirmedTrueOrderByLessonIdAscPositionAsc(lessonIds)) skillIds.computeIfAbsent(s.getLessonId(), k -> new ArrayList<>()).add(s.getId());
        }
        List<PublishedLessonSummary> published = new ArrayList<>();
        for (var l : lessons) {
            int count = stopsPerPlay.getOrDefault(l.getId(), 0);
            if (count == 0) continue;
            published.add(new PublishedLessonSummary(l.getId(), l.getVersion(), course, Subject.valueOf(l.getSubject().toUpperCase()), kdate(l.getDate()),
                    l.getTitle() == null ? "Lesson" : l.getTitle(), count, skillIds.getOrDefault(l.getId(), List.of())));
        }
        var done = completions.findByChildId(child.getId()).stream().map(c -> new LessonCompletionInfo(c.getLessonId(), c.getLevel(), c.getStarsEarned(), c.getStarsTotal(), c.isMostStopsTwoStars())).toList();
        Map<String, List<Integer>> parentUnlocked = new HashMap<>();
        for (var u : unlocks.findByChildId(child.getId())) parentUnlocked.computeIfAbsent(u.getLessonId(), k -> new ArrayList<>()).add(u.getLevel());
        List<MapAssembler.ReviewCandidate> review = new ArrayList<>();
        progress.weakByLesson(child, lessons).forEach((lessonId, b) -> review.add(new MapAssembler.ReviewCandidate(b.skillId(), b.name(), lessonId, lessonId + ":1:1")));
        var c = ChildService.dto(child);
        var assembled = MapAssembler.INSTANCE.assemble(c, published, done, review, parentUnlocked, kdate(from), kdate(to), kdate(today));
        return withTeacherIslands(assembled, child, today);
    }

    /**
     * §6 screen 14's island, attached to the assembled map. Null rather than an empty list when there is none: the
     * shared codec omits a null field, which is what keeps the body valid against `MapResponse.schema.json`
     * (`additionalProperties: false` at the root). Read it as `teacherIslands.orEmpty()`.
     */
    private MapResponse withTeacherIslands(MapResponse assembled, Entities.ChildEntity child, LocalDate today) {
        if (!flags.isOn(child.getSchoolId(), FlagKeys.TEACHER_QUESTIONS)) return assembled;
        var islands = teacherQuestions.islandsFor(child, today);
        if (islands.isEmpty()) return assembled;
        return new MapResponse(assembled.getChildId(), assembled.getCourse(), assembled.getFrom(), assembled.getTo(),
                assembled.getToday(), assembled.getIslands(), islands);
    }

    static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }
}
