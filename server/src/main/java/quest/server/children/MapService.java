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
import quest.server.content.LessonRepository;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.content.StopRepository;

/** `GET /children/{id}/map` — the shared §7 assembler fed from the database. */
@Service
public class MapService {
    private final LessonRepository lessons; private final PlayRepository plays; private final StopRepository stops; private final SkillRepository skills;
    private final LessonCompletionRepository completions; private final ParentUnlockRepository unlocks; private final ProgressService progress;

    public MapService(LessonRepository lessons, PlayRepository plays, StopRepository stops, SkillRepository skills, LessonCompletionRepository completions, ParentUnlockRepository unlocks, ProgressService progress) {
        this.lessons = lessons; this.plays = plays; this.stops = stops; this.skills = skills; this.completions = completions; this.unlocks = unlocks; this.progress = progress;
    }

    public MapResponse map(Entities.ChildEntity child, LocalDate from, LocalDate to, LocalDate today) {
        var course = Course.Companion.parse(child.courseId());
        List<PublishedLessonSummary> published = new ArrayList<>();
        for (var l : lessons.findByCourseIdAndStatus(child.courseId(), "published")) {
            int stopsPerPlay = plays.findByLessonIdAndLevelAndVariant(l.getId(), 1, 0).map(p -> stops.findByPlayIdOrderByPosition(p.getId()).size()).orElse(0);
            if (stopsPerPlay == 0) continue;
            var skillIds = skills.findByLessonIdAndConfirmedTrueOrderByPosition(l.getId()).stream().map(s -> s.getId()).toList();
            published.add(new PublishedLessonSummary(l.getId(), l.getVersion(), course, Subject.valueOf(l.getSubject().toUpperCase()), kdate(l.getDate()), l.getTitle() == null ? "Lesson" : l.getTitle(), stopsPerPlay, skillIds));
        }
        var done = completions.findByChildId(child.getId()).stream().map(c -> new LessonCompletionInfo(c.getLessonId(), c.getLevel(), c.getStarsEarned(), c.getStarsTotal(), c.isMostStopsTwoStars())).toList();
        Map<String, List<Integer>> parentUnlocked = new HashMap<>();
        for (var u : unlocks.findByChildId(child.getId())) parentUnlocked.computeIfAbsent(u.getLessonId(), k -> new ArrayList<>()).add(u.getLevel());
        List<MapAssembler.ReviewCandidate> review = new ArrayList<>();
        progress.weakByLesson(child).forEach((lessonId, b) -> review.add(new MapAssembler.ReviewCandidate(b.skillId(), b.name(), lessonId, lessonId + ":1:1")));
        var c = ChildService.dto(child);
        return MapAssembler.INSTANCE.assemble(c, published, done, review, parentUnlocked, kdate(from), kdate(to), kdate(today));
    }

    static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }
}
