package quest.server.hints;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dashboard.ApproveHintsRequest;
import quest.api.dashboard.PracticePlayView;
import quest.api.dashboard.SmartHintsView;
import quest.api.dashboard.StopHintsView;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PlayEntity;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.content.StopRepository;

/**
 * Feature 30: everything a teacher does with the hints and the practice sets her lesson was built with — read, edit,
 * approve, reject.
 *
 * <p><strong>Nothing here talks to a model.</strong> The writing happened once, in the pipeline
 * ({@link quest.server.analysis.SmartHintsGenerator}); this is the human half, and it is the half that decides what a
 * child ever sees: {@link LessonStore#assemble} publishes a row only once {@code approvedAt} is set, so an unapproved
 * hint is a draft on her screen and nothing more.
 *
 * <p>Scoping is the caller's: every method takes the {@link LessonEntity} that
 * {@link quest.server.tenancy.TeacherScope#requireLesson} already resolved, and the `school` filter is on every read
 * besides. A stop or a play of another lesson is a 404 here, not somebody else's row edited by accident.
 */
@Service
public class SmartHints {
    private final StopHintsRepository hints; private final PracticePlayRepository practice;
    private final PlayRepository plays; private final StopRepository stops; private final SkillRepository skills;
    private final LessonStore store; private final Json json; private final Clock clock;

    public SmartHints(StopHintsRepository hints, PracticePlayRepository practice, PlayRepository plays, StopRepository stops,
                      SkillRepository skills, LessonStore store, Json json, Clock clock) {
        this.hints = hints; this.practice = practice; this.plays = plays; this.stops = stops; this.skills = skills;
        this.store = store; this.json = json; this.clock = clock;
    }

    // ---------------------------------------------------------------- what she reads
    /** The hints of every stop that has them, in play then stop order, and the practice sets beside them. */
    public SmartHintsView view(LessonEntity lesson) {
        var located = located(lesson.getId());
        var rows = new LinkedHashMap<String, Entities.StopHintsEntity>();
        for (var row : hints.findByLessonId(lesson.getId())) rows.put(row.getStopId(), row);
        var out = new ArrayList<StopHintsView>();
        for (var e : located.entrySet()) {
            var row = rows.get(e.getKey());
            if (row != null) out.add(view(e.getValue(), row));
        }
        return new SmartHintsView(lesson.getId(), out, practiceViews(lesson.getId()));
    }

    // ---------------------------------------------------------------- what she changes
    /**
     * Her two hints for one stop, in place of the model's.
     *
     * <p><strong>The edit clears the approval.</strong> `approvedAt` means "a teacher has read exactly these words",
     * and the moment the words change it is no longer true — so her own edit goes back in the queue beside the
     * model's, and she approves it like any other.
     */
    @Transactional
    public StopHintsView save(LessonEntity lesson, String stopId, List<String> written) {
        var located = located(lesson.getId());
        var stop = located.get(stopId);
        if (stop == null) throw ApiException.notFound("stop");
        var errors = HintRules.errors(stopId, written == null ? null : written.stream().map(String::strip).toList(), HintRules.answerWords(stop.stop()));
        if (!errors.isEmpty()) throw ApiException.badRequest(String.join("; ", errors));
        var row = hints.findById(stopId).orElseGet(() -> {
            var fresh = new Entities.StopHintsEntity();
            fresh.setStopId(stopId); fresh.setLessonId(lesson.getId()); fresh.setSchoolId(lesson.getSchoolId()); fresh.setCreatedAt(clock.instant());
            return fresh;
        });
        var arr = json.array(); written.stream().map(String::strip).forEach(arr::add);
        row.setHintsJson(arr.toString());
        row.setApprovedBy(null); row.setApprovedAt(null);
        return view(stop, hints.save(row));
    }

    /** Approves the named stops, or every stop of the lesson that has hints. Answers how many rows it signed. */
    @Transactional
    public int approve(LessonEntity lesson, ApproveHintsRequest request, String userId) {
        var mine = new LinkedHashMap<String, Entities.StopHintsEntity>();
        for (var row : hints.findByLessonId(lesson.getId())) mine.put(row.getStopId(), row);
        var chosen = request == null || request.getStopIds() == null ? new ArrayList<>(mine.keySet()) : request.getStopIds();
        var now = clock.instant();
        int signed = 0;
        for (String stopId : chosen) {
            var row = mine.get(stopId);
            if (row == null) throw ApiException.notFound("stop");
            row.setApprovedBy(userId); row.setApprovedAt(now);
            hints.save(row);
            signed++;
        }
        return signed;
    }

    /** This practice set may be published; the next publish of the lesson carries it. */
    @Transactional
    public PracticePlayView approvePractice(LessonEntity lesson, String playId, String userId) {
        var row = requirePractice(lesson, playId);
        row.setApprovedBy(userId); row.setApprovedAt(clock.instant());
        practice.save(row);
        return practiceViews(lesson.getId()).stream().filter(v -> v.getPlayId().equals(playId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("practice play"));
    }

    /**
     * She does not want this one. The play goes with its row, because a practice set nobody will approve is a level
     * the editor would keep offering her and a variant the next build would have to skip.
     */
    @Transactional
    public void rejectPractice(LessonEntity lesson, String playId) {
        var row = requirePractice(lesson, playId);
        practice.delete(row);
        plays.findById(playId).ifPresent(play -> { stops.deleteAll(stops.findByPlayIdOrderByPosition(play.getId())); plays.delete(play); });
    }

    // ---------------------------------------------------------------- what the map asks
    /**
     * The approved practice set of each (lesson, skill), as the `playId` a review island carries —
     * `"<lessonId>:1:<variant>"`, the same shape as the generic Again play's `"<lessonId>:1:1"`, so the app resolves
     * one exactly as it resolves the other ({@link quest.api.dto.PublishedLesson#play}).
     *
     * <p>One query for a whole map, and no play decoded: the island needs an id, not content.
     */
    public Map<String, String> approvedPracticeIds(Collection<String> lessonIds) {
        if (lessonIds == null || lessonIds.isEmpty()) return Map.of();
        var out = new LinkedHashMap<String, String>();
        for (Object[] row : practice.approvedByLesson(lessonIds)) {
            String lessonId = (String) row[0], skillId = (String) row[1];
            int variant = ((Number) row[2]).intValue();
            out.putIfAbsent(key(lessonId, skillId), lessonId + ":1:" + variant);
        }
        return out;
    }

    /** The key {@link #approvedPracticeIds} answers under: one practice set per lesson and skill. */
    public static String key(String lessonId, String skillId) { return lessonId + "\u0000" + skillId; }

    // ---------------------------------------------------------------- helpers
    /** One stop of one play of this lesson: where it sits, so the teacher's list reads like her lesson. */
    private record Located(int level, int variant, Stop stop) {}

    /** Every answerable stop of the lesson's levels and Again variant, by stop id, in the order a child meets them. */
    private Map<String, Located> located(String lessonId) {
        var out = new LinkedHashMap<String, Located>();
        for (PlayEntity entity : store.plays(lessonId)) {
            if (entity.getVariant() > 1) continue;                       // a practice set is approved as a whole, not hinted
            Play play = store.play(entity);
            for (Stop stop : HintRules.answerableStops(play)) out.put(stop.getId(), new Located(entity.getLevel(), entity.getVariant(), stop));
        }
        return out;
    }

    private StopHintsView view(Located located, Entities.StopHintsEntity row) {
        return new StopHintsView(row.getStopId(), located.level(), located.variant(), located.stop().getType(),
                HintRules.question(located.stop()), String.join(", ", HintRules.answerWords(located.stop())),
                json.strings(row.getHintsJson()), row.isApproved(), row.getApprovedBy(),
                row.getApprovedAt() == null ? null : row.getApprovedAt().toEpochMilli());
    }

    private List<PracticePlayView> practiceViews(String lessonId) {
        var rows = practice.findByLessonIdOrderBySkillIdAsc(lessonId);
        if (rows.isEmpty()) return List.of();
        var byId = new LinkedHashMap<String, PlayEntity>();
        for (var p : store.plays(lessonId)) byId.put(p.getId(), p);
        var names = new LinkedHashMap<String, String>();
        for (var s : skills.findByLessonIdAndConfirmedTrueOrderByPosition(lessonId)) names.put(s.getId(), s.getName());
        var out = new ArrayList<PracticePlayView>();
        for (var row : rows) {
            var entity = byId.get(row.getPlayId());
            if (entity == null) continue;
            out.add(new PracticePlayView(row.getPlayId(), row.getSkillId(), names.getOrDefault(row.getSkillId(), "Practice"),
                    entity.getVariant(), store.play(entity), row.isApproved(), row.getApprovedBy(),
                    row.getApprovedAt() == null ? null : row.getApprovedAt().toEpochMilli()));
        }
        out.sort(java.util.Comparator.comparingInt(PracticePlayView::getVariant));
        return out;
    }

    private Entities.PracticePlayEntity requirePractice(LessonEntity lesson, String playId) {
        return practice.findById(playId).filter(row -> row.getLessonId().equals(lesson.getId()))
                .orElseThrow(() -> ApiException.notFound("practice play"));
    }
}
