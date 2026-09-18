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
import quest.api.LessonImage;
import quest.api.LessonSource;
import quest.api.dto.Bilingual;
import quest.api.dto.BilingualList;
import quest.api.dto.ModelAnswer;
import quest.api.dto.PageImage;
import quest.api.dto.SourceKind;
import quest.api.dto.StopTip;
import quest.api.dto.Theme;
import quest.server.content.Entities.PageImageEntity;
import quest.server.content.PageImageRepository;
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
import quest.server.analysis.LessonSteps;
import quest.api.LessonStepInfo;
import quest.api.PipelineStep;
import quest.api.StepStatus;
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

import quest.server.tenancy.TenantContext;
import quest.server.tenancy.TenantGuard;

/** Everything behind `/admin/lessons/**`: lifecycle draft → analysing → needs_review → generating → review → published. */
@Service
public class AdminLessonService {
    private final LessonRepository lessons; private final SourceFileRepository sourceFiles; private final SkillRepository skills; private final PlayRepository plays; private final StopRepository stops; private final ParentPanelRepository panels;
    private final AnalysisCacheRepository analysisCache; private final LessonStore store; private final AnalysisService analysisService; private final GenerationService generation; private final LessonPipeline pipeline; private final LessonState state;
    private final FileStore files; private final Json json; private final PageImageRepository pageImages; private final String publicUrl; private final LessonSteps steps;
    private final TenantContext tenant; private final TenantGuard guard;
    private final quest.server.schools.SchoolService schools;
    private final quest.server.tenancy.ClassRepository sections; private final quest.server.auth.UserRepository people;

    public AdminLessonService(LessonRepository lessons, SourceFileRepository sourceFiles, SkillRepository skills, PlayRepository plays, StopRepository stops, ParentPanelRepository panels, AnalysisCacheRepository analysisCache, LessonStore store, AnalysisService analysisService, GenerationService generation, LessonPipeline pipeline, LessonState state, FileStore files, Json json, PageImageRepository pageImages, quest.server.config.QuestProperties props, LessonSteps steps, TenantContext tenant, TenantGuard guard, quest.server.schools.SchoolService schools, quest.server.tenancy.ClassRepository sections, quest.server.auth.UserRepository people) {
        this.lessons = lessons; this.sourceFiles = sourceFiles; this.skills = skills; this.plays = plays; this.stops = stops; this.panels = panels; this.analysisCache = analysisCache; this.store = store; this.analysisService = analysisService; this.generation = generation; this.pipeline = pipeline; this.state = state; this.files = files; this.json = json;
        this.pageImages = pageImages; this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl(); this.steps = steps; this.tenant = tenant; this.guard = guard; this.schools = schools; this.sections = sections; this.people = people;
    }

    /**
     * `findOneById`, never `findById`: Hibernate filters do not apply to `em.find`, so a `findById` would hand a
     * Teacher of school A a lesson of school B by id. Every lesson, skill, play, stop, panel and file the admin API
     * touches is reached through here, which is what scopes them all.
     */
    public LessonEntity get(String id) { return lessons.findOneById(id).orElseThrow(() -> ApiException.notFound("lesson")); }

    /** The same lesson, for a caller who is about to change it: MANAGERIAL reads her school but writes nothing (§5). */
    private LessonEntity getForWrite(String id) { guard.requireLessonWrite(); return get(id); }

    /**
     * §6 screen 8, All lessons. The rows are already scoped — the tenant filter gives an Admin who picked a school,
     * and every Teacher or Managerial caller, only that school's lessons — so `filter.schoolId` is a <em>narrowing</em>
     * of what the caller may already see, never a way to reach past it: a Teacher of A naming B gets an empty list.
     *
     * <p>Each row carries its school's name so the Admin's school column needs no request per row. Four statements
     * for the whole page whatever its length: the lessons, the schools they belong to, their source files and their
     * pipeline ledgers. `UsageQueryCountTest` pins that.
     */
    public List<AdminLesson> list(LessonFilter f) { return list(f, l -> true); }

    /**
     * The same page, narrowed to the rows a caller may actually reach. `GET /teacher/lessons` (N2.4b) hands in
     * {@link quest.server.teacher.TeacherLessonService}'s reading of `TeacherScope.requireLesson` — she wrote it, or
     * she holds the assignment on its class and subject — so the teacher's list is her lessons rather than her
     * school's. The predicate runs over the already-tenant-scoped rows and before the four bulk reads, so a narrowed
     * list costs no more statements than the Admin's.
     */
    public List<AdminLesson> list(LessonFilter f, java.util.function.Predicate<LessonEntity> reachable) {
        String schoolId = f.getSchoolId() == null || f.getSchoolId().isBlank() ? null : f.getSchoolId().trim();
        String classId = f.getClassId() == null || f.getClassId().isBlank() ? null : f.getClassId().trim();
        var rows = lessons.findAllByOrderByDateDescCreatedAtDesc().stream().filter(l -> {
            if (!reachable.test(l)) return false;
            var course = Course.Companion.parse(l.getCourseId());
            if (schoolId != null && !schoolId.equals(l.getSchoolId())) return false;
            // §6 screen 12 "her lessons only, by class": the rows are already scoped to the caller's school, so a
            // class of another school simply matches nothing rather than widening what she can see.
            if (classId != null && !classId.equals(l.getClassId())) return false;
            if (f.getCurriculum() != null && course.getCurriculum() != f.getCurriculum()) return false;
            if (f.getGrade() != null && course.getGrade() != f.getGrade()) return false;
            if (f.getSubject() != null && !l.getSubject().equals(f.getSubject().name().toLowerCase())) return false;
            if (f.getFrom() != null && l.getDate().isBefore(jdate(f.getFrom()))) return false;
            if (f.getTo() != null && l.getDate().isAfter(jdate(f.getTo()))) return false;
            return true;
        }).toList();
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(LessonEntity::getId).toList();
        var names = schools.namesOf(rows.stream().map(LessonEntity::getSchoolId).distinct().toList());
        var filesByLesson = new java.util.LinkedHashMap<String, List<quest.server.content.Entities.SourceFileEntity>>();
        for (var file : sourceFiles.findByLessonIdInOrderByLessonIdAscCreatedAtAsc(ids))
            filesByLesson.computeIfAbsent(file.getLessonId(), k -> new ArrayList<>()).add(file);
        var stepsByLesson = steps.listAll(ids);
        return rows.stream().map(l -> toAdmin(l, false, names.get(l.getSchoolId()),
                filesByLesson.getOrDefault(l.getId(), List.of()), stepsByLesson.getOrDefault(l.getId(), List.of()))).toList();
    }

    @Transactional
    public AdminLesson create(CreateLessonRequest req, Principals.User admin) {
        if (req.getGrade() < 1 || req.getGrade() > 3) throw ApiException.badRequest("Grade must be 1, 2 or 3.");
        if (req.getPracticeLength() < 5 || req.getPracticeLength() > 12) throw ApiException.badRequest("Practice length must be 5–12 stops.");
        var e = new LessonEntity();
        e.setId(UUID.randomUUID().toString()); e.setCourseId(new Course(req.getCurriculum(), req.getGrade()).getKey()); e.setSubject(req.getSubject().name().toLowerCase());
        e.setDate(jdate(req.getDate())); e.setStatus("draft"); e.setVersion(0); e.setNotes(req.getNotes()); e.setPracticeLength(req.getPracticeLength());
        e.setCreatedBy(admin == null ? null : admin.email()); e.setCreatedAt(Instant.now()); e.setUpdatedAt(Instant.now());
        var schoolId = tenant.writeSchoolId();
        var curriculum = req.getCurriculum().name().toLowerCase(); var subject = req.getSubject().name().toLowerCase();
        // D14: the lesson goes into a section the caller holds a teaching assignment on, or 403 (`TenantGuard`).
        var target = guard.lessonTarget(req.getClassId(), curriculum, req.getGrade(), subject);
        e.setSchoolId(schoolId);
        e.setClassId(target.section().getId()); e.setTeacherId(target.teacherId());
        if (req.getTitle() != null && !req.getTitle().isBlank()) e.setTitle(req.getTitle().trim());
        if (req.getSource() == LessonSource.MANUAL) {
            // hand-written: straight to review with an empty Level 1; the admin adds stops, writes or generates the rest
            e.setSource("manual"); e.setStatus("review"); if (e.getTitle() == null) e.setTitle("Untitled lesson");
            lessons.save(e);
            store.savePlay(e.getId(), emptyPlay(e, 1, 0), "manual", 0);
            return toAdmin(e, true);
        }
        return toAdmin(lessons.save(e), true);
    }

    // not @Transactional: the failed-upload branch must commit its step/status writes after the inner transaction rolled back
    public LessonStatus upload(String id, List<AnalysisService.Upload> uploads) {
        var lesson = getForWrite(id);
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (!manual(lesson)) steps.ensure(id);
        try { analysisService.upload(lesson, uploads); }
        catch (ApiException e) {
            // an unreadable / too large file is a failed upload step, not a dead end: the lesson shows "Replace file"
            if (manual(lesson) || !(e.error().code().equals("unreadable_file") || e.error().code().equals("too_large") || e.error().code().equals("no_teaching_content"))) throw e;
            String message = LessonSteps.Messages.of(e.error().code(), e.error().message());
            steps.mark(id, PipelineStep.UPLOAD, "error", e.error().code(), message);
            state.fail(id, e.error().code(), message);
            return LessonStatus.ERROR;
        }
        var kinds = sourceFiles.findByLessonIdOrderByCreatedAt(id).stream().filter(f -> f.getDeletedAt() == null).map(quest.server.content.Entities.SourceFileEntity::getKind).toList();
        lesson.setSource(kinds.contains("pptx") ? "slides" : kinds.contains("pdf") ? "pdf" : kinds.isEmpty() ? lesson.getSource() : "images");
        lesson.setErrorCode(null); lesson.setErrorMessage(null);
        lessons.save(lesson);
        steps.resetFrom(id, PipelineStep.ANALYZE); steps.done(id, PipelineStep.UPLOAD);
        return LessonStatus.DRAFT;
    }

    public LessonStatus analyze(String id) {
        var lesson = getForWrite(id);
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (sourceFiles.findByLessonIdOrderByCreatedAt(id).stream().noneMatch(f -> f.getDeletedAt() == null)) throw ApiException.badRequest("Upload the slides first.");
        steps.ensure(id); steps.done(id, PipelineStep.UPLOAD);
        state.set(id, LessonStatus.ANALYZING);
        pipeline.analyzeAsync(id);
        return LessonStatus.ANALYZING;
    }

    @Transactional
    public LessonStatus confirmSkills(String id, List<ConfirmedSkill> confirmed) {
        var lesson = getForWrite(id);
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
        lesson.setStatus("generating"); lesson.setErrorCode(null); lesson.setErrorMessage(null); lesson.setUpdatedAt(Instant.now()); lessons.save(lesson);
        if (!manual(lesson)) { steps.ensure(id); steps.done(id, PipelineStep.SKILLS); steps.resetFrom(id, PipelineStep.GENERATE_L1); }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() { pipeline.generateAsync(id); }
        });
        return LessonStatus.GENERATING;
    }

    @Transactional
    public Stop updateStop(String stopId, Stop stop) {
        var se = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        var lesson = getForWrite(se.getLessonId()); requireReview(lesson);
        var pe = plays.findById(se.getPlayId()).orElseThrow();
        Play play = store.play(pe);
        List<Stop> list = new ArrayList<>(play.getStops());
        int index = -1; for (int i = 0; i < list.size(); i++) if (list.get(i).getId().equals(stopId)) index = i;
        if (index < 0) throw ApiException.notFound("stop");
        if (!stop.getId().equals(stopId)) throw ApiException.badRequest("The stop id can't change.");
        list.set(index, stop);
        Play updated = new Play(play.getLevel(), play.getVariant(), play.getKind(), play.getTheme(), list, play.getId());
        validatePlay(lesson, updated, play.getLevel());
        store.savePlay(lesson.getId(), updated, pe.getPromptVersion(), pe.getSeed());
        touch(lesson);
        return stop;
    }

    // ---------------------------------------------------------------- manual authoring
    private static boolean manual(LessonEntity l) { return "manual".equals(l.getSource()); }

    private void validatePlay(LessonEntity lesson, Play play, int level) {
        var v = SchemaValidator.INSTANCE.validate(play, level, java.util.Collections.emptySet(), manual(lesson));
        if (!v.getErrors().isEmpty()) throw ApiException.badRequest(String.join("; ", v.getErrors()));
    }

    /** An empty play with a theme that fits the subject; stops come from the admin. */
    private static Play emptyPlay(LessonEntity lesson, int level, int variant) {
        boolean math = "math".equals(lesson.getSubject());
        var theme = math ? new Theme("Number pot", "Number soup", "🥣", "The number soup is ready!") : new Theme("Story pot", "Story stew", "🍲", "The story stew is ready!");
        return new Play(level, variant, math ? SourceKind.MATH : SourceKind.MIXED, theme, List.of(), null);
    }

    @Transactional
    public AdminPlay createPlay(String lessonId, int level, int variant) {
        var lesson = getForWrite(lessonId); requireReview(lesson);
        if (level < 1 || level > 3 || variant < 0 || variant > 1 || (variant == 1 && level != 1)) throw ApiException.badRequest("Levels are 1–3; only Level 1 has an Again variant.");
        if (plays.findByLessonIdAndLevelAndVariant(lessonId, level, variant).isPresent()) throw ApiException.badRequest("That level already exists.");
        if (!manual(lesson)) { lesson.setSource("manual"); } // a hand-added level makes the lesson hand-edited: lenient rules from here on
        var pe = store.savePlay(lessonId, emptyPlay(lesson, level, variant), "manual", 0);
        touch(lesson);
        return new AdminPlay(pe.getId(), level, variant, store.play(pe), pe.getPromptVersion(), pe.getGeneratedAt().toEpochMilli());
    }

    @Transactional
    public Stop addStop(String playId, Stop stop) {
        var pe = plays.findById(playId).orElseThrow(() -> ApiException.notFound("play"));
        var lesson = getForWrite(pe.getLessonId()); requireReview(lesson);
        Play play = store.play(pe);
        String prefix = quest.server.analysis.StopIds.prefix(lesson.getId(), play.getLevel(), play.getVariant());
        String id = stop.getId() == null || stop.getId().isBlank() || !stop.getId().startsWith(prefix) ? prefix + "m" + UUID.randomUUID().toString().substring(0, 6) : stop.getId();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.tree(json.encodeShared(stop, Stop.Companion.serializer()));
        node.put("id", id);
        Stop withId = json.decodeShared(node.toString(), Stop.Companion.serializer());
        List<Stop> list = new ArrayList<>(play.getStops());
        // the exit ticket stays last
        int at = !list.isEmpty() && list.get(list.size() - 1) instanceof Stop.ExitTicket && !(withId instanceof Stop.ExitTicket) ? list.size() - 1 : list.size();
        list.add(at, withId);
        Play updated = new Play(play.getLevel(), play.getVariant(), play.getKind(), play.getTheme(), list, play.getId());
        if (!manual(lesson)) lesson.setSource("manual");
        validatePlay(lesson, updated, play.getLevel());
        store.savePlay(lesson.getId(), updated, pe.getPromptVersion(), pe.getSeed());
        touch(lesson);
        return withId;
    }

    @Transactional
    public void deleteStop(String stopId) {
        var se = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        var lesson = getForWrite(se.getLessonId()); requireReview(lesson);
        var pe = plays.findById(se.getPlayId()).orElseThrow();
        Play play = store.play(pe);
        List<Stop> list = play.getStops().stream().filter(s -> !s.getId().equals(stopId)).toList();
        if (!manual(lesson)) lesson.setSource("manual");
        store.savePlay(lesson.getId(), new Play(play.getLevel(), play.getVariant(), play.getKind(), play.getTheme(), list, play.getId()), pe.getPromptVersion(), pe.getSeed());
        touch(lesson);
    }

    @Transactional
    public Play reorderStops(String playId, List<String> ids) {
        var pe = plays.findById(playId).orElseThrow(() -> ApiException.notFound("play"));
        var lesson = getForWrite(pe.getLessonId()); requireReview(lesson);
        Play play = store.play(pe);
        var byId = new java.util.LinkedHashMap<String, Stop>(); play.getStops().forEach(s -> byId.put(s.getId(), s));
        if (ids.size() != byId.size() || !new java.util.HashSet<>(ids).equals(byId.keySet())) throw ApiException.badRequest("The new order must contain every stop exactly once.");
        List<Stop> list = ids.stream().map(byId::get).toList();
        Play updated = new Play(play.getLevel(), play.getVariant(), play.getKind(), play.getTheme(), list, play.getId());
        validatePlay(lesson, updated, play.getLevel());
        store.savePlay(lesson.getId(), updated, pe.getPromptVersion(), pe.getSeed());
        touch(lesson);
        return updated;
    }

    /** Stores a picture in the lesson's image folder and registers it as a page image the app can show. */
    @Transactional
    public LessonImage uploadImage(String lessonId, String fileName, String mimeType, byte[] bytes) {
        var lesson = getForWrite(lessonId);
        if (bytes.length == 0) throw ApiException.badRequest("Empty file.");
        if (bytes.length > 8 * 1024 * 1024) throw ApiException.badRequest("Pictures must be under 8 MB.");
        int width = 0, height = 0;
        try { var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)); if (img == null) throw ApiException.badRequest("That file isn't a PNG or JPEG picture."); width = img.getWidth(); height = img.getHeight(); }
        catch (java.io.IOException e) { throw ApiException.badRequest("That file isn't a PNG or JPEG picture."); }
        int n = (int) pageImages.findByLessonIdOrderByPageNumber(lessonId).stream().filter(i -> i.getId().contains(":img-")).count() + 1;
        String id = lessonId.substring(0, 8) + ":img-" + n + "-" + UUID.randomUUID().toString().substring(0, 4);
        String ext = mimeType != null && mimeType.contains("png") ? "png" : "jpg";
        var stored = files.put("pages/" + lessonId + "/" + id.substring(9) + "." + ext, bytes, ext.equals("png") ? "image/png" : "image/jpeg");
        var e = new PageImageEntity();
        e.setId(id); e.setLessonId(lessonId); e.setPageNumber(1000 + n); e.setStoragePath(stored.path()); e.setWidth(width); e.setHeight(height); e.setDescription(fileName == null ? "" : fileName);
        pageImages.save(e);
        touch(lesson);
        return new LessonImage(id, publicUrl + "/media/pages/" + id);
    }

    /** Prompt A on the admin's text, then Prompt B for every level that does not exist yet (+ the Again variant) and Prompt C. */
    @Transactional
    public LessonStatus generateFromText(String id, String text) {
        var lesson = getForWrite(id);
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (text == null || text.trim().length() < 20) throw ApiException.badRequest("Write a few sentences about the lesson first (at least 20 characters).");
        if (LessonState.status(lesson) == LessonStatus.PUBLISHED) throw ApiException.badRequest("Unpublish the lesson before generating.");
        lesson.setNotes(lesson.getNotes()); lesson.setStatus("generating"); lesson.setUpdatedAt(Instant.now()); lessons.save(lesson);
        final String t = text.trim();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() { pipeline.generateFromTextAsync(id, t); }
        });
        return LessonStatus.GENERATING;
    }

    /** Manual lessons publish with whatever the admin wrote: missing levels repeat Level 1, a missing panel is derived from the stops. */
    private void completeManual(LessonEntity lesson) {
        var all = store.plays(lesson.getId());
        var l1 = all.stream().filter(p -> p.getLevel() == 1 && p.getVariant() == 0).findFirst().orElseThrow(() -> ApiException.badRequest("Level 1 needs at least one stop."));
        Play base = store.play(l1);
        if (base.getStops().isEmpty()) throw ApiException.badRequest("Level 1 needs at least one stop.");
        validatePlay(lesson, base, 1);
        for (int[] t : new int[][] {{2, 0}, {3, 0}, {1, 1}}) {
            var existing = all.stream().filter(p -> p.getLevel() == t[0] && p.getVariant() == t[1]).findFirst();
            if (existing.isPresent() && !store.play(existing.get()).getStops().isEmpty()) { validatePlay(lesson, store.play(existing.get()), t[0]); continue; }
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.tree(json.encodeShared(base, Play.Companion.serializer()));
            node.put("level", t[0]); node.put("variant", t[1]);
            String from = quest.server.analysis.StopIds.prefix(lesson.getId(), 1, 0), to = quest.server.analysis.StopIds.prefix(lesson.getId(), t[0], t[1]);
            var copy = json.decodeShared(node.toString().replace(from, to), Play.Companion.serializer());
            store.savePlay(lesson.getId(), copy, "manual-copy", 0);
        }
        if (panels.findById(lesson.getId()).isEmpty()) {
            var tips = new ArrayList<StopTip>(); var answers = new ArrayList<ModelAnswer>();
            for (Stop s : base.getStops()) {
                tips.add(new StopTip(s.getId(), s.getParentTip().getEn(), s.getParentTip().getAr()));
                if (s instanceof Stop.OpenAnswer o) answers.add(new ModelAnswer(s.getId(), o.getModelAnswer()));
                if (s instanceof Stop.Retell r) answers.add(new ModelAnswer(s.getId(), r.getModelAnswer()));
            }
            String title = lesson.getTitle() == null ? "this lesson" : lesson.getTitle();
            var panel = new ParentPanel(new BilingualList(List.of("Practise " + title + " together."), List.of("تدرّبوا معًا على " + title + ".")),
                    List.of(new Bilingual("Read each question aloud and let your child answer first.", "اقرأ كل سؤال بصوت عالٍ ودع طفلك يجيب أولًا.")),
                    List.of(new Bilingual("Ask your child to explain why the answer is right.", "اطلب من طفلك أن يشرح لماذا الإجابة صحيحة.")),
                    tips, answers);
            store.savePanel(lesson.getId(), panel);
        }
    }

    public Stop regenerateStop(String stopId) {
        var se = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        var lesson = getForWrite(se.getLessonId()); requireReview(lesson);
        var pe = plays.findById(se.getPlayId()).orElseThrow();
        var stop = generation.regenerateStop(lesson, pe, stopId);
        touch(lesson);
        return stop;
    }

    public Play regeneratePlay(String playId) {
        var pe = plays.findById(playId).orElseThrow(() -> ApiException.notFound("play"));
        var lesson = getForWrite(pe.getLessonId()); requireReview(lesson);
        var play = generation.regeneratePlay(lesson, pe);
        touch(lesson);
        return play;
    }

    @Transactional
    public ParentPanel updatePanel(String id, ParentPanel panel) {
        var lesson = getForWrite(id); requireReview(lesson);
        if (panel.getObjectives().getEn().size() != panel.getObjectives().getAr().size()) throw ApiException.badRequest("Objectives need the same number of English and Arabic lines.");
        store.savePanel(id, panel); touch(lesson);
        return panel;
    }

    @Transactional
    public AdminLesson publish(String id) {
        var lesson = getForWrite(id);
        if (LessonState.status(lesson) != LessonStatus.REVIEW) throw ApiException.badRequest("Only a lesson in review can be published.");
        if (manual(lesson)) completeManual(lesson);
        if (store.assemble(lesson) == null) throw ApiException.badRequest("All three levels, the Again variant and the parent panel must exist.");
        lesson.setStatus("published"); lesson.setVersion(lesson.getVersion() + 1); lesson.setPublishedAt(Instant.now()); lesson.setUpdatedAt(Instant.now());
        return toAdmin(lessons.save(lesson), true);
    }

    @Transactional
    public AdminLesson unpublish(String id) {
        var lesson = getForWrite(id);
        if (LessonState.status(lesson) != LessonStatus.PUBLISHED) throw ApiException.badRequest("The lesson isn't published.");
        lesson.setStatus("review"); lesson.setUpdatedAt(Instant.now());
        return toAdmin(lessons.save(lesson), true);
    }

    // ---------------------------------------------------------------- retry
    public LessonStatus retry(String id) {
        var lesson = getForWrite(id);
        if (manual(lesson)) throw ApiException.badRequest("Hand-written lessons have no pipeline to retry — press Generate the other levels instead.");
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (LessonState.status(lesson) == LessonStatus.PUBLISHED) throw ApiException.badRequest("Unpublish the lesson first.");
        var from = pipeline.firstToRun(id);
        if (from == PipelineStep.SKILLS) throw ApiException.badRequest("Confirm the skills to continue.");
        state.set(id, from.ordinal() <= PipelineStep.ANALYZE.ordinal() ? LessonStatus.ANALYZING : LessonStatus.GENERATING);
        pipeline.retryAsync(id);
        return LessonState.status(get(id));
    }

    public LessonStatus retryStep(String id, PipelineStep step) {
        var lesson = getForWrite(id);
        if (manual(lesson)) throw ApiException.badRequest("Hand-written lessons have no pipeline to retry.");
        if (!editable(lesson)) throw ApiException.badRequest("Wait for the current job to finish.");
        if (LessonState.status(lesson) == LessonStatus.PUBLISHED) throw ApiException.badRequest("Unpublish the lesson first.");
        if (step == PipelineStep.SKILLS) throw ApiException.badRequest("Confirm the skills on the Skills step.");
        pipeline.backfill(id); steps.ensure(id);
        if (step.ordinal() > PipelineStep.SKILLS.ordinal() && !steps.isDone(id, PipelineStep.SKILLS)) throw ApiException.badRequest("Confirm the skills first.");
        if (steps.isDone(id, step)) throw ApiException.badRequest(step.getLabel() + " is already done.");
        state.set(id, step.ordinal() <= PipelineStep.ANALYZE.ordinal() ? LessonStatus.ANALYZING : LessonStatus.GENERATING);
        pipeline.retryStepAsync(id, step);
        return LessonState.status(get(id));
    }

    @Transactional
    public void deleteFiles(String id) {
        getForWrite(id);
        for (var f : sourceFiles.findByLessonIdOrderByCreatedAt(id)) if (f.getDeletedAt() == null) { files.delete(f.getStoragePath()); f.setDeletedAt(Instant.now()); sourceFiles.save(f); }
    }

    /** Removes the lesson, its uploaded files and page images in the bucket, and (by cascade) skills, plays, stops, panel, steps. The AI caches are keyed by file hash and stay. */
    @Transactional
    public void delete(String id) {
        var lesson = getForWrite(id);
        if (LessonState.status(lesson) == LessonStatus.PUBLISHED) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "published", "Unpublish the lesson first; published lessons can't be deleted.");
        if (!editable(lesson)) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "running", "Wait for the current job to finish.");
        deleteFiles(id);
        for (var img : pageImages.findByLessonIdOrderByPageNumber(id)) files.delete(img.getStoragePath());
        lessons.delete(lesson);
    }

    /** Deletes every lesson in error. */
    @Transactional
    public int deleteFailed() {
        guard.requireLessonWrite();
        int n = 0;
        for (var l : lessons.findAllByOrderByDateDescCreatedAtDesc()) if (LessonState.status(l) == LessonStatus.ERROR) { delete(l.getId()); n++; }
        return n;
    }

    // ---------------------------------------------------------------- DTO

    /** One lesson: its school's name, its files and its ledger cost one lookup each, which is what a detail view can afford. */
    public AdminLesson toAdmin(LessonEntity l, boolean full) {
        return toAdmin(l, full, schools.namesOf(List.of(l.getSchoolId())).get(l.getSchoolId()),
                sourceFiles.findByLessonIdOrderByCreatedAt(l.getId()), steps.list(l.getId()));
    }

    /**
     * The same, with the per-lesson rows handed in: the list path reads the files and the ledgers of every lesson in
     * one query each, so §6 screen 8 — every lesson of every school — does not cost three statements per row.
     */
    AdminLesson toAdmin(LessonEntity l, boolean full, String schoolName,
                        List<quest.server.content.Entities.SourceFileEntity> lessonFiles,
                        List<quest.server.content.Entities.LessonStepEntity> lessonSteps) {
        var course = Course.Companion.parse(l.getCourseId());
        var status = LessonState.status(l);
        var fileInfos = lessonFiles.stream().map(f -> new SourceFileInfo(f.getId(), f.getFileName(), f.getFileHash(), f.getPageCount(), f.isCacheHit(), f.getDeletedAt() != null)).toList();
        var error = l.getErrorCode() == null ? null : new ApiError(l.getErrorCode(), l.getErrorMessage() == null ? "" : l.getErrorMessage());
        var source = LessonSource.valueOf(l.getSource().toUpperCase());
        if (full && !manual(l) && !lessonFiles.isEmpty()) { pipeline.backfill(l.getId()); lessonSteps = steps.list(l.getId()); }
        var stepInfos = lessonSteps.stream().map(s -> new LessonStepInfo(LessonSteps.parse(s.getStep()), StepStatus.valueOf(s.getStatus().toUpperCase()), s.getAttempt(), s.getErrorCode(), s.getErrorMessage(), s.getUpdatedAt().toEpochMilli())).toList();
        var currentStep = l.getCurrentStep() == null ? null : LessonSteps.parse(l.getCurrentStep());
        // N2.1's editor badge, free of a query and with one source of truth: `AnalysisService` records it beside the
        // cache lookup that answers it (V8), so the text path and a copy are as truthful as an upload — and a
        // hand-written lesson that paid for its own analysis says so rather than claiming a saving it never made.
        boolean analyzedBefore = l.isAnalysisCacheHit();
        var type = quest.api.dashboard.LessonType.valueOf(l.getType().toUpperCase());
        if (!full) return new AdminLesson(l.getId(), course, Subject.valueOf(l.getSubject().toUpperCase()), kdate(l.getDate()), status, l.getVersion(), l.getNotes(), l.getTitle(), l.getTokenUsage(), l.getTokensSaved(),
                fileInfos, null, List.of(), List.of(), null, error, l.getPublishedAt() == null ? null : l.getPublishedAt().toEpochMilli(), l.getCreatedAt().toEpochMilli(), source, stepInfos, currentStep, List.of(),
                l.getSchoolId(), schoolName,
                // The list path names no class and no teacher: either would be a lookup per row, and §6 screen 8 shows
                // a school column rather than a class one. The editor reads the full lesson, which fills both.
                l.getClassId(), null, l.getTeacherId(), null, type, analyzedBefore);
        SourceAnalysis analysis = l.getSourceHash() == null ? null : analysisCache.findById(CacheKeys.INSTANCE.analysisKey(l.getSourceHash())).map(c -> json.decodeShared(c.getAnalysisJson(), SourceAnalysis.Companion.serializer())).orElse(null);
        var skillDtos = skills.findByLessonIdOrderByPosition(l.getId()).stream().map(this::skill).toList();
        var playDtos = store.plays(l.getId()).stream().map(p -> new AdminPlay(p.getId(), p.getLevel(), p.getVariant(), store.play(p), p.getPromptVersion(), p.getGeneratedAt().toEpochMilli())).toList();
        var panel = panels.findById(l.getId()).map(p -> json.decodeShared(p.getPanelJson(), ParentPanel.Companion.serializer())).orElse(null);
        var images = pageImages.findByLessonIdOrderByPageNumber(l.getId()).stream().map(i -> new PageImage(i.getId(), publicUrl + "/media/pages/" + i.getId(), i.getWidth(), i.getHeight(), i.getDescription())).toList();
        var section = l.getClassId() == null ? null : sections.findOneById(l.getClassId()).orElse(null);
        var teacher = l.getTeacherId() == null ? null : people.findById(l.getTeacherId()).orElse(null);
        return new AdminLesson(l.getId(), course, Subject.valueOf(l.getSubject().toUpperCase()), kdate(l.getDate()), status, l.getVersion(), l.getNotes(), l.getTitle(), l.getTokenUsage(), l.getTokensSaved(),
                fileInfos, analysis, skillDtos, playDtos, panel, error, l.getPublishedAt() == null ? null : l.getPublishedAt().toEpochMilli(), l.getCreatedAt().toEpochMilli(), source, stepInfos, currentStep, images,
                l.getSchoolId(), schoolName,
                l.getClassId(), section == null ? null : section.getName(), l.getTeacherId(),
                teacher == null ? null : teacher.getDisplayName(), type, analyzedBefore);
    }

    private ExtractedSkill skill(SkillEntity s) {
        Unsure unsure = null;
        if (s.getUnsureJson() != null) { var n = json.tree(s.getUnsureJson()); List<String> cands = new ArrayList<>(); n.path("candidates").forEach(c -> cands.add(c.asText())); unsure = new Unsure(cands, n.path("question").asText("")); }
        List<String> examples = json.strings(s.getExamplesJson()); List<Integer> slidesNos = new ArrayList<>(); json.tree(s.getSlideNumbersJson()).forEach(n -> slidesNos.add(n.asInt()));
        return new ExtractedSkill(s.getId(), s.getName(), Subject.valueOf(s.getSubject().toUpperCase()), s.getMethod(), examples, slidesNos, s.isConfirmed() ? 1.0 : s.getConfidence(), unsure);
    }

    private static boolean editable(LessonEntity l) { var s = LessonState.status(l); return s != LessonStatus.ANALYZING && s != LessonStatus.GENERATING && s != LessonStatus.UPLOADING; }
    private static void requireReview(LessonEntity l) { var s = LessonState.status(l); if (s != LessonStatus.REVIEW && s != LessonStatus.PUBLISHED && s != LessonStatus.ERROR && s != LessonStatus.PAUSED) throw ApiException.badRequest("Generate the levels first."); }
    private void touch(LessonEntity l) { l.setUpdatedAt(Instant.now()); if (LessonState.status(l) == LessonStatus.PUBLISHED) l.setStatus("review"); lessons.save(l); }
    static LocalDate jdate(kotlinx.datetime.LocalDate d) { return LocalDate.of(d.getYear(), d.getMonthNumber(), d.getDayOfMonth()); }
    static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }
}
