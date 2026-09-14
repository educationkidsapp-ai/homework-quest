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
import quest.api.dto.Subject;
import quest.api.dto.Theme;
import quest.server.config.Json;

/** Reads and writes plays / stops / panels and assembles the immutable {@code PublishedLesson} JSON the app downloads. */
@Service
public class LessonStore {
    private final LessonRepository lessons; private final SkillRepository skills; private final PlayRepository plays; private final StopRepository stops;
    private final ParentPanelRepository panels; private final PageImageRepository pageImages; private final Json json; private final String publicUrl;

    public LessonStore(LessonRepository lessons, SkillRepository skills, PlayRepository plays, StopRepository stops, ParentPanelRepository panels, PageImageRepository pageImages, Json json, quest.server.config.QuestProperties props) {
        this.lessons = lessons; this.skills = skills; this.plays = plays; this.stops = stops; this.panels = panels; this.pageImages = pageImages; this.json = json;
        this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl();
    }

    /** Stores a validated play (replacing any existing one for that level/variant) and its stop rows. */
    @Transactional
    public Entities.PlayEntity savePlay(String lessonId, Play play, String promptVersion, int seed) {
        var existing = plays.findByLessonIdAndLevelAndVariant(lessonId, play.getLevel(), play.getVariant());
        existing.ifPresent(e -> { stops.deleteAll(stops.findByPlayIdOrderByPosition(e.getId())); plays.delete(e); });
        var e = new Entities.PlayEntity();
        e.setId(existing.map(Entities.PlayEntity::getId).orElse(UUID.randomUUID().toString()));
        e.setLessonId(lessonId); e.setLevel(play.getLevel()); e.setVariant(play.getVariant());
        e.setPlayJson(json.encodeShared(play, Play.Companion.serializer())); e.setPromptVersion(promptVersion); e.setSeed(seed); e.setGeneratedAt(Instant.now());
        plays.save(e);
        int pos = 0;
        for (Stop s : play.getStops()) {
            var se = new Entities.StopEntity();
            se.setId(s.getId()); se.setPlayId(e.getId()); se.setLessonId(lessonId); se.setPosition(pos++); se.setType(s.getType()); se.setCategory(s.getCategory().name());
            se.setTitle(s.getTitle()); se.setIngredient(s.getIngredient().getEmoji() + " " + s.getIngredient().getName());
            se.setContentJson(json.encodeShared(s, Stop.Companion.serializer())); se.setParentTipEn(s.getParentTip().getEn()); se.setParentTipAr(s.getParentTip().getAr());
            stops.save(se);
        }
        return e;
    }

    @Transactional
    public void savePanel(String lessonId, ParentPanel panel) {
        var e = panels.findById(lessonId).orElseGet(() -> { var n = new Entities.ParentPanelEntity(); n.setLessonId(lessonId); return n; });
        e.setPanelJson(json.encodeShared(panel, ParentPanel.Companion.serializer())); e.setUpdatedAt(Instant.now());
        panels.save(e);
    }

    public Play play(Entities.PlayEntity e) { return json.decodeShared(e.getPlayJson(), Play.Companion.serializer()); }
    public List<Entities.PlayEntity> plays(String lessonId) { return plays.findByLessonIdOrderByLevelAscVariantAsc(lessonId); }

    /** The immutable lesson the app downloads (`GET /lessons/{id}`). Null until all three levels, the variant and the panel exist. */
    public PublishedLesson assemble(Entities.LessonEntity lesson) {
        var all = plays(lesson.getId());
        List<Play> levels = new ArrayList<>(); Play variant = null;
        for (var p : all) { var play = play(p); if (p.getVariant() == 0) levels.add(play); else if (p.getLevel() == 1) variant = play; }
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
        return new PublishedLesson(lesson.getId(), Math.max(1, lesson.getVersion()), course, Subject.valueOf(lesson.getSubject().toUpperCase()), date,
                lesson.getTitle() == null ? "Lesson" : lesson.getTitle(), kind, theme, skillRefs, levels, variant, panel, images);
    }

    public String assembleJson(Entities.LessonEntity lesson) {
        var pl = assemble(lesson);
        return pl == null ? null : json.encodeShared(pl, PublishedLesson.Companion.serializer());
    }

    public static Curriculum curriculum(String s) { return Curriculum.valueOf(s.toUpperCase()); }
}
