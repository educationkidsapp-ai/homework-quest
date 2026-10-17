package quest.server.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.Course;
import quest.api.dto.Curriculum;
import quest.api.dto.PageImage;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.PublishedLesson;
import quest.api.dto.SkillRef;
import quest.api.dto.SourceKind;
import quest.api.dto.Stop;
import quest.api.dto.StopKt;
import quest.api.dto.Subject;
import quest.api.dto.Theme;
import quest.server.config.Json;

/** Reads and writes plays / stops / panels and assembles the immutable {@code PublishedLesson} JSON the app downloads. */
@Service
public class LessonStore {
    private final LessonRepository lessons; private final SkillRepository skills; private final PlayRepository plays; private final StopRepository stops;
    private final ParentPanelRepository panels; private final PageImageRepository pageImages; private final Json json; private final String publicUrl;
    /** Feature 30: read-only here, and only ever the approved rows — see {@link #assemble}. */
    private final quest.server.hints.StopHintsRepository hints; private final quest.server.hints.PracticePlayRepository practice;
    /** Feature 30's switch, read where the body is assembled: a school with it off publishes no hints at all. */
    private final quest.server.flags.FeatureFlags flags;

    public LessonStore(LessonRepository lessons, SkillRepository skills, PlayRepository plays, StopRepository stops, ParentPanelRepository panels, PageImageRepository pageImages, Json json, quest.server.config.QuestProperties props,
                       quest.server.hints.StopHintsRepository hints, quest.server.hints.PracticePlayRepository practice,
                       quest.server.flags.FeatureFlags flags) {
        this.lessons = lessons; this.skills = skills; this.plays = plays; this.stops = stops; this.panels = panels; this.pageImages = pageImages; this.json = json;
        this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl();
        this.hints = hints; this.practice = practice; this.flags = flags;
    }

    /**
     * Stores a validated play (replacing any existing one for that level/variant) and its stop rows.
     *
     * <p>CR5: {@code Stop.teacherText} is a server-side reading of the JSON, never part of it — `Play.schema.json`
     * forbids the property — so the play is stripped of it before it is encoded. The prose already saved for a stop
     * (`stops.text`, V9) survives this rewrite <em>only</em> when that stop's JSON comes out byte-identical: an
     * edited stop's prose no longer describes what is stored, so it is dropped and the next read re-describes it,
     * while reordering a play or editing its neighbour leaves every other stop's prose where the teacher left it.
     */
    @Transactional
    public Entities.PlayEntity savePlay(String lessonId, Play play, String promptVersion, int seed) {
        var existing = plays.findByLessonIdAndLevelAndVariant(lessonId, play.getLevel(), play.getVariant());
        var keptText = new java.util.HashMap<String, Entities.StopEntity>();
        var was = new java.util.HashMap<String, String>();
        existing.ifPresent(e -> {
            var old = stops.findByPlayIdOrderByPosition(e.getId());
            for (var se : old) if (se.getText() != null) keptText.put(se.getId() + "\u0000" + se.getContentJson(), se);
            was.putAll(answerableJson(json.decodeShared(e.getPlayJson(), Play.Companion.serializer())));
            stops.deleteAll(old); plays.delete(e);
        });
        Play stored = StopKt.withoutTeacherText(play);
        // Feature 30: the hints of a question this rewrite has taken away — or rewritten. They described *that*
        // question, so leaving them would publish help for a stop that no longer exists, or help written for an
        // answer that has changed (the teacher's edit-by-text and Regenerate level both keep the stop's id). The
        // deletion is ours to make: `stop_hints` has no foreign key into `stops`, because an exitTicket's questions
        // have no row there at all (V52).
        var now = answerableJson(stored);
        var gone = new java.util.HashSet<String>();
        was.forEach((id, before) -> { if (!before.equals(now.get(id))) gone.add(id); });
        gone.forEach(hints::deleteById);
        var e = new Entities.PlayEntity();
        e.setId(existing.map(Entities.PlayEntity::getId).orElse(UUID.randomUUID().toString()));
        e.setLessonId(lessonId); e.setLevel(stored.getLevel()); e.setVariant(stored.getVariant());
        e.setPlayJson(json.encodeShared(stored, Play.Companion.serializer())); e.setPromptVersion(promptVersion); e.setSeed(seed); e.setGeneratedAt(Instant.now());
        plays.save(e);
        int pos = 0;
        for (Stop s : stored.getStops()) {
            var se = new Entities.StopEntity();
            se.setId(s.getId()); se.setPlayId(e.getId()); se.setLessonId(lessonId); se.setPosition(pos++); se.setType(s.getType()); se.setCategory(s.getCategory().name());
            se.setTitle(s.getTitle()); se.setIngredient(s.getIngredient().getEmoji() + " " + s.getIngredient().getName());
            se.setContentJson(json.encodeShared(s, Stop.Companion.serializer())); se.setParentTipEn(s.getParentTip().getEn()); se.setParentTipAr(s.getParentTip().getAr());
            var kept = keptText.get(se.getId() + "\u0000" + se.getContentJson());
            if (kept != null) { se.setText(kept.getText()); se.setTextUpdatedAt(kept.getTextUpdatedAt()); }
            stops.save(se);
        }
        return e;
    }

    /**
     * The teacher-facing prose of every stop of a lesson: what she saved, or — for a stop nobody has put into words
     * yet — the deterministic reading of its JSON. Returned, never written: a stop the pipeline rewrites describes
     * itself afresh rather than carrying a sentence about content that has gone.
     */
    public java.util.Map<String, String> teacherText(String lessonId) {
        var byId = new java.util.HashMap<String, String>();
        for (var se : stops.findByLessonId(lessonId)) if (se.getText() != null) byId.put(se.getId(), se.getText());
        return byId;
    }

    /** The play with each stop carrying its teacher-facing prose (saved, or described from the JSON). */
    public Play withTeacherText(Play play, java.util.Map<String, String> saved) {
        List<Stop> described = play.getStops().stream()
                .map(s -> StopKt.withTeacherText(s, saved.getOrDefault(s.getId(), StopText.describe(s)))).toList();
        return new Play(play.getLevel(), play.getVariant(), play.getKind(), play.getTheme(), described, play.getId());
    }

    @Transactional
    public void savePanel(String lessonId, ParentPanel panel) {
        var e = panels.findById(lessonId).orElseGet(() -> { var n = new Entities.ParentPanelEntity(); n.setLessonId(lessonId); return n; });
        e.setPanelJson(json.encodeShared(panel, ParentPanel.Companion.serializer())); e.setUpdatedAt(Instant.now());
        panels.save(e);
    }

    /**
     * Every stop of a play that a child answers, the three inside an exitTicket included — what can carry hints —
     * by id, each with its own encoded content. The content is what decides whether hints written for it still
     * describe it: an id that stays while its question changes is exactly the case a set of approved hints must not
     * survive.
     */
    private java.util.Map<String, String> answerableJson(Play play) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (Stop s : play.getStops()) {
            out.put(s.getId(), json.encodeShared(s, Stop.Companion.serializer()));
            if (s instanceof Stop.ExitTicket et)
                for (Stop q : et.getQuestions()) out.put(q.getId(), json.encodeShared(q, Stop.Companion.serializer()));
        }
        return out;
    }

    public Play play(Entities.PlayEntity e) { return json.decodeShared(e.getPlayJson(), Play.Companion.serializer()); }
    public List<Entities.PlayEntity> plays(String lessonId) { return plays.findByLessonIdOrderByLevelAscVariantAsc(lessonId); }

    /** The immutable lesson the app downloads (`GET /lessons/{id}`). Null until all three levels, the variant and the panel exist. */
    public PublishedLesson assemble(Entities.LessonEntity lesson) {
        var all = plays(lesson.getId());
        List<Play> levels = new ArrayList<>(); Play variant = null;
        // Feature 30: variant 2 and up is a targeted practice set, which is published separately and only when it is
        // approved — reading it as "the Again variant" would have handed every child a practice set instead.
        for (var p : all) { var play = play(p); if (p.getVariant() == 0) levels.add(play); else if (p.getLevel() == 1 && p.getVariant() == 1) variant = play; }
        if (levels.size() < 3 || variant == null) return null;
        var panelJson = panels.findById(lesson.getId()).map(Entities.ParentPanelEntity::getPanelJson).orElse(null);
        if (panelJson == null) return null;
        var panel = json.decodeShared(panelJson, ParentPanel.Companion.serializer());
        var skillRefs = skills.findByLessonIdAndConfirmedTrueOrderByPosition(lesson.getId()).stream()
                .map(s -> new SkillRef(s.getId(), s.getName(), Subject.valueOf(s.getSubject().toUpperCase()), s.getMethod())).toList();
        var images = pageImages.findByLessonIdOrderByPageNumber(lesson.getId()).stream()
                .map(i -> new PageImage(i.getId(), publicUrl + "/media/pages/" + i.getId(), i.getWidth(), i.getHeight(), i.getDescription())).toList();
        var course = Course.Companion.parse(lesson.getCourseId());
        var first = levels.get(0);
        Theme theme = first.getTheme(); SourceKind kind = first.getKind();
        var date = new kotlinx.datetime.LocalDate(lesson.getDate().getYear(), lesson.getDate().getMonthValue(), lesson.getDate().getDayOfMonth());
        // N4.3's four exam fields are left at their homework defaults here: this method knows nothing about exams,
        // and `ExamPlays.decorate` fills them for the one caller that serves a lesson to a child.
        // Feature 30's switch belongs here and not only on the teacher's routes: an Admin who turns `smartHints` off
        // because a hint turned out to be wrong must stop the hints reaching children, and while it is off she
        // cannot unapprove them either (the whole controller answers 404). Off is off, in the body as on the map.
        boolean smartHints = flags.isOn(lesson.getSchoolId(), quest.server.flags.FlagKeys.SMART_HINTS);
        return new PublishedLesson(lesson.getId(), Math.max(1, lesson.getVersion()), course, Subject.valueOf(lesson.getSubject().toUpperCase()), date,
                lesson.getTitle() == null ? "Lesson" : lesson.getTitle(), kind, theme, skillRefs, levels, variant, panel, images,
                "homework", false, false, null,
                smartHints ? approvedHints(lesson.getId()) : java.util.Map.of(),
                smartHints ? approvedPractice(lesson.getId(), all, skillRefs) : List.of());
    }

    /**
     * Feature 30: the hints a teacher has approved, by stop id — and nothing else. An unapproved row is work in
     * progress on her screen and must never be in a body a child's phone can download, which is why this reads
     * `approvedAt` rather than the row's existence.
     */
    private java.util.Map<String, List<String>> approvedHints(String lessonId) {
        var out = new java.util.LinkedHashMap<String, List<String>>();
        for (var row : hints.findByLessonId(lessonId))
            if (row.isApproved()) out.put(row.getStopId(), json.strings(row.getHintsJson()));
        return out;
    }

    /** Feature 30: the approved practice sets, each with the confirmed skill it practises. */
    private List<quest.api.dto.PracticePlay> approvedPractice(String lessonId, List<Entities.PlayEntity> all, List<SkillRef> skillRefs) {
        var rows = practice.findByLessonIdOrderBySkillIdAsc(lessonId).stream().filter(quest.server.hints.Entities.PracticePlayEntity::isApproved).toList();
        if (rows.isEmpty()) return List.of();
        var byId = new java.util.HashMap<String, Entities.PlayEntity>();
        for (var p : all) byId.put(p.getId(), p);
        var names = new java.util.HashMap<String, String>();
        for (var s : skillRefs) names.put(s.getId(), s.getName());
        var out = new ArrayList<quest.api.dto.PracticePlay>();
        for (var row : rows) {
            var entity = byId.get(row.getPlayId());
            if (entity == null) continue;                       // the play was deleted and the cascade has not run yet
            out.add(new quest.api.dto.PracticePlay(row.getSkillId(), names.getOrDefault(row.getSkillId(), "Practice"), play(entity)));
        }
        out.sort(java.util.Comparator.comparingInt(p -> p.getPlay().getVariant()));
        return out;
    }

    public String assembleJson(Entities.LessonEntity lesson) {
        var pl = assemble(lesson);
        return pl == null ? null : encode(pl);
    }

    /** The same encoding, for a caller that has decorated the assembled lesson (N4.3's exam fields). */
    public String encode(PublishedLesson lesson) { return json.encodeShared(lesson, PublishedLesson.Companion.serializer()); }

    public static Curriculum curriculum(String s) { return Curriculum.valueOf(s.toUpperCase()); }
}
