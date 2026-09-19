package quest.server.children;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import quest.api.dto.ProgressResponse;
import quest.api.dto.SkillProgress;
import quest.api.dto.Stop;
import quest.api.dto.StopCategory;
import quest.api.dto.Subject;
import quest.api.progress.Band;
import quest.api.progress.ProgressBands;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;

/** Bands per skill from first tries on single-answer stops (last 14), words never percentages. */
@Service
public class ProgressService {
    public record SkillBand(String skillId, String name, Subject subject, String lessonId, Band band, Double accuracy, int attempts, Long lastPractised) {}

    private final AttemptRepository attempts; private final SchoolLessons schoolLessons; private final SkillRepository skills; private final PlayRepository plays; private final LessonStore store;
    private final StreakRepository streaks; private final StickerRepository stickers; private final quest.server.grading.GradingService grading;

    public ProgressService(AttemptRepository attempts, SchoolLessons schoolLessons, SkillRepository skills, PlayRepository plays, LessonStore store, StreakRepository streaks, StickerRepository stickers, quest.server.grading.GradingService grading) {
        this.attempts = attempts; this.schoolLessons = schoolLessons; this.skills = skills; this.plays = plays; this.store = store; this.streaks = streaks; this.stickers = stickers; this.grading = grading;
    }

    public List<SkillBand> skillBands(Entities.ChildEntity child) { return skillBands(child, schoolLessons.publishedFor(child)); }

    /**
     * The same bands over lessons the caller already has (the map assembles them once and hands them over). The plays
     * and the confirmed skills of every lesson are fetched in one query each, so the cost does not grow per lesson.
     */
    public List<SkillBand> skillBands(Entities.ChildEntity child, List<LessonEntity> lessons) {
        var mine = attempts.findByChildIdOrderByAnsweredAtDesc(child.getId());
        var published = lessons.stream().sorted(Comparator.comparing(LessonEntity::getDate)).toList();
        var ids = published.stream().map(LessonEntity::getId).toList();
        Map<String, List<quest.server.content.Entities.PlayEntity>> playsByLesson = new LinkedHashMap<>();
        Map<String, List<quest.server.content.Entities.SkillEntity>> skillsByLesson = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (var p : plays.findByLessonIdInOrderByLessonIdAscLevelAscVariantAsc(ids)) playsByLesson.computeIfAbsent(p.getLessonId(), k -> new ArrayList<>()).add(p);
            for (var s : skills.findByLessonIdInAndConfirmedTrueOrderByLessonIdAscPositionAsc(ids)) skillsByLesson.computeIfAbsent(s.getLessonId(), k -> new ArrayList<>()).add(s);
        }
        List<SkillBand> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (var lesson : published) {
            var single = singleStopIds(playsByLesson.getOrDefault(lesson.getId(), List.of()));
            var firstTries = mine.stream().filter(a -> a.getLessonId().equals(lesson.getId()) && single.contains(a.getStopId()) && a.getAttemptNumber() == 1).toList();
            List<Boolean> results = firstTries.stream().map(Entities.AttemptEntity::isCorrect).toList();
            var acc = ProgressBands.INSTANCE.accuracy(results);
            var last = firstTries.isEmpty() ? null : firstTries.get(0).getAnsweredAt().toEpochMilli();
            for (var s : skillsByLesson.getOrDefault(lesson.getId(), List.of())) {
                if (!seen.add(s.getId())) continue;
                out.add(new SkillBand(s.getId(), s.getName(), Subject.valueOf(s.getSubject().toUpperCase()), lesson.getId(),
                        acc == null ? null : ProgressBands.INSTANCE.band(acc), acc, results.size(), last));
            }
        }
        return out;
    }

    private Set<String> singleStopIds(List<quest.server.content.Entities.PlayEntity> lessonPlays) {
        Set<String> ids = new HashSet<>();
        for (var pe : lessonPlays) for (Stop s : store.play(pe).getStops()) {
            if (s.getCategory() == StopCategory.SINGLE) ids.add(s.getId());
            if (s instanceof Stop.ExitTicket et) for (var q : et.getQuestions()) if (q.getCategory() == StopCategory.SINGLE) ids.add(q.getId());
        }
        return ids;
    }

    /**
     * N4.1 (teacher prompt §7): `results` carries the score, the band and the teacher's comment for every lesson she
     * has <strong>released</strong>, and nothing for the rest — the flag is the release, not the attempt. It is a
     * new field with a default, so an app that has not been updated reads exactly what it read before. Child mode
     * still sees no number anywhere: this is the parent's half of the report.
     */
    public ProgressResponse progress(Entities.ChildEntity child) {
        var published = schoolLessons.publishedFor(child);
        var bands = skillBands(child, published);
        var list = bands.stream().map(b -> new SkillProgress(b.skillId(), b.name(), b.subject(), b.band() == null ? null : b.band().name(),
                b.accuracy() == null ? null : ProgressBands.INSTANCE.accuracyWords(b.accuracy()), b.attempts(), b.lastPractised())).toList();
        var weak = bands.stream().filter(b -> b.band() == Band.NEEDS_ANOTHER_LOOK).map(SkillBand::skillId).toList();
        var streak = streaks.findById(child.getId()).orElse(null);
        kotlinx.datetime.LocalDate last = streak == null || streak.getLastPlayedDate() == null ? null
                : new kotlinx.datetime.LocalDate(streak.getLastPlayedDate().getYear(), streak.getLastPlayedDate().getMonthValue(), streak.getLastPlayedDate().getDayOfMonth());
        var stickerKeys = stickers.findByChildIdOrderByEarnedAt(child.getId()).stream().map(Entities.StickerEntity::getStickerKey).toList();
        return new ProgressResponse(child.getId(), list, weak, streak == null ? 0 : streak.getCurrentDays(), last, stickerKeys,
                grading.releasedFor(child, published));
    }

    /** Weak skills grouped by lesson (one review island per lesson). */
    public Map<String, SkillBand> weakByLesson(Entities.ChildEntity child) { return weakByLesson(child, schoolLessons.publishedFor(child)); }

    public Map<String, SkillBand> weakByLesson(Entities.ChildEntity child, List<LessonEntity> lessons) {
        Map<String, SkillBand> out = new LinkedHashMap<>();
        for (var b : skillBands(child, lessons)) if (b.band() == Band.NEEDS_ANOTHER_LOOK) out.putIfAbsent(b.lessonId(), b);
        return out;
    }
}
