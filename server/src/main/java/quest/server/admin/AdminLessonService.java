package quest.server.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.AdminLesson;
import quest.api.AdminPlay;
import quest.api.CacheKeys;
import quest.api.ConfirmedSkill;
import quest.api.CreateLessonRequest;
import quest.api.LessonFilter;
import quest.api.SourceFileInfo;
import quest.api.dto.ApiError;
import quest.api.dto.Course;
import quest.api.dto.ExtractedSkill;
import quest.api.dto.LessonStatus;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.SourceAnalysis;
import quest.api.dto.Stop;
import quest.api.dto.Subject;
import quest.api.dto.Unsure;
import quest.api.validation.SchemaValidator;
import quest.server.analysis.AnalysisCacheRepository;
import quest.server.analysis.AnalysisService;
import quest.server.analysis.CacheEntities;
import quest.server.analysis.GenerationService;
import quest.server.analysis.LessonPipeline;
import quest.server.analysis.LessonState;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PlayEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.ParentPanelRepository;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.content.SourceFileRepository;
import quest.server.content.StopRepository;
import quest.server.files.FileStore;

/** Everything behind `/admin/lessons/**`: lifecycle draft → analysing → needs_review → generating → review → published. */
@Service
public class AdminLessonService {
    private final LessonRepository lessons; private final SourceFileRepository sourceFiles; private final SkillRepository skills; private final PlayRepository plays; private final StopRepository stops; private final ParentPanelRepository panels;
    private final AnalysisCacheRepository analysisCache; private final LessonStore store; private final AnalysisService analysisService; private final GenerationService generation; private final LessonPipeline pipeline; private final LessonState state;
    private final FileStore files; private final Json json;

    public AdminLessonService(LessonRepository lessons, SourceFileRepository sourceFiles, SkillRepository skills, PlayRepository plays, StopRepository stops, ParentPanelRepository panels, AnalysisCacheRepository analysisCache, LessonStore store, AnalysisService analysisService, GenerationService generation, LessonPipeline pipeline, LessonState state, FileStore files, Json json) {
        this.lessons = lessons; this.sourceFiles = sourceFiles; this.skills = skills; this.plays = plays; this.stops = stops; this.panels = panels; this.analysisCache = analysisCache; this.store = store; this.analysisService = analysisService; this.generation = generation; this.pipeline = pipeline; this.state = state; this.files = files; this.json = json;
    }

    public LessonEntity get(String id) { return lessons.findById(id).orElseThrow(() -> ApiException.notFound("lesson")); }

    public List<AdminLesson> list(LessonFilter f) {
        return lessons.findAllByOrderByDateDescCreatedAtDesc().stream().filter(l -> {
            var course = Course.Companion.parse(l.getCourseId());
            if (f.getCurriculum() != null && course.getCurriculum() != f.getCurriculum()) return false;
            if (f.getGrade() != null && course.getGrade() != f.getGrade()) return false;
            if (f.getSubject() != null && !l.getSubject().equals(f.getSubject().name().toLowerCase())) return false;
            if (f.getStatus() != null && !l.getStatus().equals(LessonState.name(f.getStatus()))) return false;
            if (f.getFrom() != null && l.getDate().isBefore(jdate(f.getFrom()))) return false;
            if (f.getTo() != null && l.getDate().isAfter(jdate(f.getTo()))) return false;
            return true;
        }).map(l -> toAdmin(l, false)).toList();
    }

    @Transactional
    public AdminLesson create(CreateLessonRequest req, Principals.Admin admin) {
        if (req.getGrade() < 1 || req.getGrade() > 3) throw ApiException.badRequest("Grade must be 1, 2 or 3.");
        if (req.getPracticeLength() < 5 || req.getPracticeLength() > 12) throw ApiException.badRequest("Practice length must be 5–12 stops.");
        var e = new LessonEntity();
        e.setId(UUID.randomUUID().toString()); e.setCourseId(new Course(req.getCurriculum(), req.getGrade()).getKey()); e.setSubject(req.getSubject().name().toLowerCase());
        e.setDate(jdate(req.getDate())); e.setStatus("draft"); e.setVersion(0); e.setNotes(req.getNotes()); e.setPracticeLength(req.getPracticeLength());
        e.setCreatedBy(admin == null ? null : admin.email()); e.setCreatedAt(Instant.now()); e.setUpdatedAt(Instant.now());
        return toAdmin(lessons.save(e), true);
    }

    @Transactional
    public LessonStatus upload(String id, List<AnalysisService.Upload> uploads) {
        var lesson = get(id);
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        analysisService.upload(lesson, uploads);
        return LessonStatus.DRAFT;
    }

    public LessonStatus analyze(String id) {
        var lesson = get(id);
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (sourceFiles.findByLessonIdOrderByCreatedAt(id).stream().noneMatch(f -> f.getDeletedAt() == null)) throw ApiException.badRequest("Upload the slides first.");
        state.set(id, LessonStatus.ANALYZING);
        pipeline.analyzeAsync(id);
        return LessonStatus.ANALYZING;
    }

    @Transactional
    public LessonStatus confirmSkills(String id, List<ConfirmedSkill> confirmed) {
        var lesson = get(id);
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (confirmed.isEmpty()) throw ApiException.badRequest("Confirm at least one skill.");
        var existing = skills.findByLessonIdOrderByPosition(id);
        existing.forEach(s -> s.setConfirmed(false));
        int pos = existing.size();
        for (var c : confirmed) {
            if (c.getName() == null || c.getName().isBlank()) throw ApiException.badRequest("Skill names can't be empty.");
            SkillEntity s = c.getId() == null ? null : existing.stream().filter(x -> x.getId().equals(c.getId())).findFirst().orElse(null);
            if (s == null) { s = new SkillEntity(); s.setId(id.substring(0, 8) + ":sk" + UUID.randomUUID().toString().substring(0, 6)); s.setLessonId(id); s.setExamplesJson("[]"); s.setSlideNumbersJson("[]"); s.setConfidence(1.0); s.setPosition(pos++); existing.add(s); }
            s.setName(c.getName().trim()); s.setSubject(c.getSubject().name().toLowerCase()); if (c.getMethod() != null && !c.getMethod().isBlank()) s.setMethod(c.getMethod().trim()); if (s.getMethod() == null) s.setMethod("as taught on the slides");
            s.setConfirmed(true); s.setUnsureJson(null);
        }
        skills.saveAll(existing);
        lesson.setStatus("generating"); lesson.setUpdatedAt(Instant.now()); lessons.save(lesson);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() { pipeline.generateAsync(id); }
        });
        return LessonStatus.GENERATING;
    }

    @Transactional
    public Stop updateStop(String stopId, Stop stop) {
        var se = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        var lesson = get(se.getLessonId()); requireReview(lesson);
        var pe = plays.findById(se.getPlayId()).orElseThrow();
        Play play = store.play(pe);
        List<Stop> list = new ArrayList<>(play.getStops());
        int index = -1; for (int i = 0; i < list.size(); i++) if (list.get(i).getId().equals(stopId)) index = i;
        if (index < 0) throw ApiException.notFound("stop");
        if (!stop.getId().equals(stopId)) throw ApiException.badRequest("The stop id can't change.");
        list.set(index, stop);
        Play updated = new Play(play.getLevel(), play.getVariant(), play.getKind(), play.getTheme(), list, play.getId());
        var v = SchemaValidator.INSTANCE.validate(updated, play.getLevel(), java.util.Collections.emptySet());
        if (!v.getErrors().isEmpty()) throw ApiException.badRequest(String.join("; ", v.getErrors()));
        store.savePlay(lesson.getId(), updated, pe.getPromptVersion(), pe.getSeed());
        touch(lesson);
        return stop;
    }

    public Stop regenerateStop(String stopId) {
        var se = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        var lesson = get(se.getLessonId()); requireReview(lesson);
        var pe = plays.findById(se.getPlayId()).orElseThrow();
        var stop = generation.regenerateStop(lesson, pe, stopId);
        touch(lesson);
        return stop;
    }

    public Play regeneratePlay(String playId) {
        var pe = plays.findById(playId).orElseThrow(() -> ApiException.notFound("play"));
        var lesson = get(pe.getLessonId()); requireReview(lesson);
        var play = generation.regeneratePlay(lesson, pe);
        touch(lesson);
        return play;
    }

    @Transactional
    public ParentPanel updatePanel(String id, ParentPanel panel) {
        var lesson = get(id); requireReview(lesson);
        if (panel.getObjectives().getEn().size() != panel.getObjectives().getAr().size()) throw ApiException.badRequest("Objectives need the same number of English and Arabic lines.");
        store.savePanel(id, panel); touch(lesson);
        return panel;
    }

    @Transactional
    public AdminLesson publish(String id) {
        var lesson = get(id);
        if (LessonState.status(lesson) != LessonStatus.REVIEW) throw ApiException.badRequest("Only a lesson in review can be published.");
        if (store.assemble(lesson) == null) throw ApiException.badRequest("All three levels, the Again variant and the parent panel must exist.");
        lesson.setStatus("published"); lesson.setVersion(lesson.getVersion() + 1); lesson.setPublishedAt(Instant.now()); lesson.setUpdatedAt(Instant.now());
        return toAdmin(lessons.save(lesson), true);
    }

    @Transactional
    public AdminLesson unpublish(String id) {
        var lesson = get(id);
        if (LessonState.status(lesson) != LessonStatus.PUBLISHED) throw ApiException.badRequest("The lesson isn't published.");
        lesson.setStatus("review"); lesson.setUpdatedAt(Instant.now());
        return toAdmin(lessons.save(lesson), true);
    }

    @Transactional
    public void deleteFiles(String id) {
        get(id);
        for (var f : sourceFiles.findByLessonIdOrderByCreatedAt(id)) if (f.getDeletedAt() == null) { files.delete(f.getStoragePath()); f.setDeletedAt(Instant.now()); sourceFiles.save(f); }
    }

    @Transactional
    public void delete(String id) {
        var lesson = get(id);
        if (LessonState.status(lesson) == LessonStatus.PUBLISHED) throw ApiException.badRequest("Unpublish the lesson first.");
        deleteFiles(id);
        lessons.delete(lesson);
    }

    // ---------------------------------------------------------------- DTO
    public AdminLesson toAdmin(LessonEntity l, boolean full) {
        var course = Course.Companion.parse(l.getCourseId());
        var status = LessonState.status(l);
        var fileInfos = sourceFiles.findByLessonIdOrderByCreatedAt(l.getId()).stream().map(f -> new SourceFileInfo(f.getId(), f.getFileName(), f.getFileHash(), f.getPageCount(), f.isCacheHit(), f.getDeletedAt() != null)).toList();
        var error = l.getErrorCode() == null ? null : new ApiError(l.getErrorCode(), l.getErrorMessage() == null ? "" : l.getErrorMessage());
        if (!full) return new AdminLesson(l.getId(), course, Subject.valueOf(l.getSubject().toUpperCase()), kdate(l.getDate()), status, l.getVersion(), l.getNotes(), l.getTitle(), l.getTokenUsage(), l.getTokensSaved(),
                fileInfos, null, List.of(), List.of(), null, error, l.getPublishedAt() == null ? null : l.getPublishedAt().toEpochMilli(), l.getCreatedAt().toEpochMilli());
        SourceAnalysis analysis = l.getSourceHash() == null ? null : analysisCache.findById(CacheKeys.INSTANCE.analysisKey(l.getSourceHash())).map(c -> json.decodeShared(c.getAnalysisJson(), SourceAnalysis.Companion.serializer())).orElse(null);
        var skillDtos = skills.findByLessonIdOrderByPosition(l.getId()).stream().map(this::skill).toList();
        var playDtos = store.plays(l.getId()).stream().map(p -> new AdminPlay(p.getId(), p.getLevel(), p.getVariant(), store.play(p), p.getPromptVersion(), p.getGeneratedAt().toEpochMilli())).toList();
        var panel = panels.findById(l.getId()).map(p -> json.decodeShared(p.getPanelJson(), ParentPanel.Companion.serializer())).orElse(null);
        return new AdminLesson(l.getId(), course, Subject.valueOf(l.getSubject().toUpperCase()), kdate(l.getDate()), status, l.getVersion(), l.getNotes(), l.getTitle(), l.getTokenUsage(), l.getTokensSaved(),
                fileInfos, analysis, skillDtos, playDtos, panel, error, l.getPublishedAt() == null ? null : l.getPublishedAt().toEpochMilli(), l.getCreatedAt().toEpochMilli());
    }

    private ExtractedSkill skill(SkillEntity s) {
        Unsure unsure = null;
        if (s.getUnsureJson() != null) { var n = json.tree(s.getUnsureJson()); List<String> cands = new ArrayList<>(); n.path("candidates").forEach(c -> cands.add(c.asText())); unsure = new Unsure(cands, n.path("question").asText("")); }
        List<String> examples = json.strings(s.getExamplesJson()); List<Integer> slidesNos = new ArrayList<>(); json.tree(s.getSlideNumbersJson()).forEach(n -> slidesNos.add(n.asInt()));
        return new ExtractedSkill(s.getId(), s.getName(), Subject.valueOf(s.getSubject().toUpperCase()), s.getMethod(), examples, slidesNos, s.isConfirmed() ? 1.0 : s.getConfidence(), unsure);
    }

    private static boolean editable(LessonEntity l) { var s = LessonState.status(l); return s != LessonStatus.ANALYZING && s != LessonStatus.GENERATING && s != LessonStatus.UPLOADING; }
    private static void requireReview(LessonEntity l) { var s = LessonState.status(l); if (s != LessonStatus.REVIEW && s != LessonStatus.PUBLISHED) throw ApiException.badRequest("Generate the levels first."); }
    private void touch(LessonEntity l) { l.setUpdatedAt(Instant.now()); if (LessonState.status(l) == LessonStatus.PUBLISHED) l.setStatus("review"); lessons.save(l); }
    static LocalDate jdate(kotlinx.datetime.LocalDate d) { return LocalDate.of(d.getYear(), d.getMonthNumber(), d.getDayOfMonth()); }
    static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }
}
