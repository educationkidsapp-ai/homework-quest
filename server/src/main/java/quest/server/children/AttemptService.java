package quest.server.children;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.AttemptUpload;
import quest.api.dto.Play;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;

/**
 * Stores uploaded attempts (idempotent by id) and derives the child's stop / lesson completions,
 * streak and stickers from them — the same rules `FakeContentApi` applies on-device.
 */
@Service
public class AttemptService {
    private final AttemptRepository attempts; private final StopCompletionRepository stopCompletions; private final LessonCompletionRepository lessonCompletions;
    private final StreakRepository streaks; private final StickerRepository stickers; private final LessonRepository lessons; private final LessonStore store;

    public AttemptService(AttemptRepository attempts, StopCompletionRepository stopCompletions, LessonCompletionRepository lessonCompletions, StreakRepository streaks, StickerRepository stickers, LessonRepository lessons, LessonStore store) {
        this.attempts = attempts; this.stopCompletions = stopCompletions; this.lessonCompletions = lessonCompletions; this.streaks = streaks; this.stickers = stickers; this.lessons = lessons; this.store = store;
    }

    @Transactional
    public int record(Entities.ChildEntity child, List<AttemptUpload> uploads) {
        int accepted = 0;
        Map<String, Instant> touchedLessons = new HashMap<>();
        for (var a : uploads) {
            if (attempts.existsById(a.getId())) continue;
            var e = new Entities.AttemptEntity();
            e.setId(a.getId()); e.setChildId(child.getId()); e.setStopId(a.getStopId()); e.setLessonId(a.getLessonId()); e.setLevel(a.getLevel());
            e.setAnswerJson(a.getAnswerJson()); e.setCorrect(a.getCorrect()); e.setAttemptNumber(a.getAttemptNumber()); e.setMistakes(a.getMistakes()); e.setStars(a.getStars());
            var at = Instant.ofEpochMilli(a.getAnsweredAt()); e.setAnsweredAt(at);
            attempts.save(e); accepted++;
            touchedLessons.merge(a.getLessonId(), at, (x, y) -> x.isAfter(y) ? x : y);

            var sc = stopCompletions.findById(new Entities.StopCompletionId(child.getId(), a.getStopId())).orElse(null);
            if (sc == null || sc.getStars() < a.getStars()) {
                if (sc == null) { sc = new Entities.StopCompletionEntity(); sc.setChildId(child.getId()); sc.setStopId(a.getStopId()); }
                sc.setLessonId(a.getLessonId()); sc.setLevel(a.getLevel()); sc.setStars(a.getStars()); sc.setCompletedAt(at);
                stopCompletions.save(sc);
            }
        }
        for (var entry : touchedLessons.entrySet()) { deriveLessonCompletions(child, entry.getKey(), entry.getValue()); touchStreak(child, entry.getValue()); }
        return accepted;
    }

    private void deriveLessonCompletions(Entities.ChildEntity child, String lessonId, Instant at) {
        var lesson = lessons.findById(lessonId).orElse(null);
        if (lesson == null) return;
        var mine = attempts.findByChildIdOrderByAnsweredAtDesc(child.getId()).stream().filter(a -> a.getLessonId().equals(lessonId)).toList();
        for (var pe : store.plays(lessonId)) {
            Play play = store.play(pe);
            int stars = 0, twoPlus = 0, n = play.getStops().size(); boolean complete = true;
            for (var stop : play.getStops()) {
                var best = mine.stream().filter(a -> a.getStopId().equals(stop.getId()) && a.getLevel() == play.getLevel()).mapToInt(Entities.AttemptEntity::getStars).max();
                if (best.isEmpty()) { complete = false; break; }
                stars += best.getAsInt(); if (best.getAsInt() >= 2) twoPlus++;
            }
            if (!complete || pe.getVariant() != 0) continue;
            var id = new Entities.LessonCompletionId(child.getId(), lessonId, play.getLevel());
            var lc = lessonCompletions.findById(id).orElseGet(() -> { var x = new Entities.LessonCompletionEntity(); x.setChildId(child.getId()); x.setLessonId(lessonId); x.setLevel(play.getLevel()); x.setCompletedAt(at); return x; });
            boolean first = lc.getStarsTotal() == 0;
            lc.setStarsEarned(Math.max(lc.getStarsEarned(), stars)); lc.setStarsTotal(n * 3); lc.setMostStopsTwoStars(lc.isMostStopsTwoStars() || twoPlus * 2 > n);
            lessonCompletions.save(lc);
            if (first) awardSticker(child, "lesson-" + lessonId + "-L" + play.getLevel(), at);
        }
    }

    private void awardSticker(Entities.ChildEntity child, String key, Instant at) {
        if (stickers.findByChildIdOrderByEarnedAt(child.getId()).stream().anyMatch(s -> s.getStickerKey().equals(key))) return;
        var s = new Entities.StickerEntity(); s.setId(UUID.randomUUID().toString()); s.setChildId(child.getId()); s.setStickerKey(key); s.setEarnedAt(at);
        stickers.save(s);
    }

    private void touchStreak(Entities.ChildEntity child, Instant at) {
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        var s = streaks.findById(child.getId()).orElseGet(() -> { var x = new Entities.StreakEntity(); x.setChildId(child.getId()); return x; });
        var last = s.getLastPlayedDate();
        if (last == null || day.isAfter(last.plusDays(1))) s.setCurrentDays(1);
        else if (day.equals(last.plusDays(1))) s.setCurrentDays(s.getCurrentDays() + 1);
        if (last == null || day.isAfter(last)) s.setLastPlayedDate(day);
        streaks.save(s);
    }
}
