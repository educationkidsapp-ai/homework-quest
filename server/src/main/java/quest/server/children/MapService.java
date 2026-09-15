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

/**
 * `GET /children/{id}/map` — the shared §7 assembler fed from the database. The lessons come from the child's school
 * (§2: every published lesson of every Class of her school with her curriculum and grade, any subject, any teacher),
 * never from a global course.
 *
 * <p>Fixed query count: classes (1) + lessons (1) + Level 1 stop counts (1) + confirmed skills (1) + completions (1)
 * + parent unlocks (1), plus what `ProgressService` needs for the review islands — the published lessons are looked
 * up once and handed to it rather than fetched again.
 */
@Service
public class MapService {
    private final SchoolLessons schoolLessons; private final StopRepository stops; private final SkillRepository skills;
    private final LessonCompletionRepository completions; private final ParentUnlockRepository unlocks; private final ProgressService progress;

    public MapService(SchoolLessons schoolLessons, StopRepository stops, SkillRepository skills, LessonCompletionRepository completions, ParentUnlockRepository unlocks, ProgressService progress) {
        this.schoolLessons = schoolLessons; this.stops = stops; this.skills = skills; this.completions = completions; this.unlocks = unlocks; this.progress = progress;
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
        return MapAssembler.INSTANCE.assemble(c, published, done, review, parentUnlocked, kdate(from), kdate(to), kdate(today));
    }

    static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }
}
