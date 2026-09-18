package quest.server.teacher;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.AdminLesson;
import quest.api.CreateLessonRequest;
import quest.api.LessonSource;
import quest.api.dto.Course;
import quest.api.dto.Curriculum;
import quest.api.dto.LessonStatus;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.Subject;
import quest.server.admin.AdminLessonService;
import quest.server.analysis.LessonState;
import quest.server.analysis.StopIds;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PageImageEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.PageImageRepository;
import quest.server.content.ParentPanelRepository;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

/**
 * `docs/teacher-flow.md` §8 — creating, moving, copying, publishing and deleting a lesson <em>as a teacher</em>.
 *
 * <p>The pipeline itself is not re-implemented: everything from the upload to the parent panel is
 * {@link AdminLessonService}, and {@link TeacherLessonController} is a thin set of `/teacher/**` aliases over it so
 * the dashboard stops signing a teacher in and calling `/admin/**`. What lives here is the four things a teacher can
 * do that an Admin has never needed.
 *
 * <ul>
 *   <li><strong>Create</strong> into a `(classId, subject)` she holds an assignment for — the same 403 for a class
 *       that is not hers and for a subject she does not teach in it, so the refusal never says which it was. The
 *       source's own feature flag is checked here rather than with {@code @FeatureFlag}, because which flag applies
 *       depends on the body: a school with `lessons.pdf` off and `lessons.manual` on can write lessons but not
 *       upload them, and one route cannot carry two keys.</li>
 *   <li><strong>Move</strong> to another day, while the lesson is unpublished. A published lesson is a 409: children
 *       may already have played it on the day it says.</li>
 *   <li><strong>Copy</strong> into a sibling class — §4's drag-to-copy. The copy is a real second lesson with ids of
 *       its own, so attempts on it are its class's results and never touch the source's; it keeps the source's
 *       `source_hash`, so the editor still shows "Analyzed before · 0 tokens" and no model is called again.</li>
 *   <li><strong>Publish to several classes at once</strong> — §8's publish sheet. Every class named that is not the
 *       lesson's own gets the copy, and then each is published on its own, with its own version.</li>
 * </ul>
 */
@Service
public class TeacherLessonService {
    private final AdminLessonService admin; private final TeacherScope scope; private final LessonRepository lessons;
    private final LessonStore store; private final PlayRepository plays; private final SkillRepository skills;
    private final ParentPanelRepository panels; private final PageImageRepository pageImages;
    private final quest.server.content.StopRepository stops;
    private final Json json; private final FeatureFlags flags;

    public TeacherLessonService(AdminLessonService admin, TeacherScope scope, LessonRepository lessons, LessonStore store,
                                PlayRepository plays, SkillRepository skills, ParentPanelRepository panels,
                                PageImageRepository pageImages, quest.server.content.StopRepository stops, Json json, FeatureFlags flags) {
        this.admin = admin; this.scope = scope; this.lessons = lessons; this.store = store; this.plays = plays;
        this.skills = skills; this.panels = panels; this.pageImages = pageImages; this.stops = stops; this.json = json; this.flags = flags;
    }

    /** The scope check every `/teacher/lessons/{id}/**` alias runs before it delegates: owner, or assigned. */
    public LessonEntity require(Principals.User caller, String lessonId) { return scope.requireLesson(caller, lessonId); }

    /** The same, as an id — what the pipeline aliases hand on to {@link AdminLessonService}. */
    public String requireId(Principals.User caller, String lessonId) { return require(caller, lessonId).getId(); }

    /**
     * §8's "All lessons" as a teacher sees it: the Admin page's rows and filters, reduced to the lessons
     * {@link TeacherScope#requireLesson} would let her open. Until N2.4b the dashboard called `GET /admin/lessons`
     * with her token, and because that route is tenant-scoped only she was shown every lesson of her school — the
     * leak N2.3b fixed for students, in the shape it takes for lessons.
     *
     * <p>The rule is {@link #reachable}, read from one statement — her assignments — rather than a lookup per row,
     * so the list costs the same for one assignment as for thirty. `schoolId` is deliberately not a parameter of the
     * route: a teacher's school is her token's, and naming another one could only ever narrow to nothing.
     */
    public List<AdminLesson> list(Principals.User caller, quest.api.LessonFilter filter) {
        return admin.list(filter, reachable(caller));
    }

    /**
     * The list's reading of `requireLesson`, as a predicate over already-scoped rows: she wrote it, or she holds the
     * assignment on its class <em>and</em> its subject. A lesson with no class is only ever its author's. ADMIN and
     * MANAGERIAL callers keep the whole school, which is what the tenant filter has already handed them.
     */
    private java.util.function.Predicate<LessonEntity> reachable(Principals.User caller) {
        if (!scope.isTeacher(caller)) return l -> true;
        var mine = new java.util.HashSet<String>();
        for (var a : scope.assignmentsOf(caller)) mine.add(key(a.getClassId(), a.getSubject()));
        return l -> caller.userId().equals(l.getTeacherId())
                || (l.getClassId() != null && mine.contains(key(l.getClassId(), l.getSubject())));
    }

    private static String key(String classId, String subject) {
        return classId + "\u0000" + (subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The lesson a stop or a play belongs to, so a `/teacher/stops/{id}` write is scoped by the same rule as a
     * `/teacher/lessons/{id}` one. A row that does not exist is a 404 before anything is loaded to edit — the same
     * silence another teacher's gets, so neither answer tells the caller which it was.
     */
    public String lessonOfStop(String stopId) {
        return stops.findById(stopId).map(quest.server.content.Entities.StopEntity::getLessonId)
                .orElseThrow(() -> ApiException.notFound("stop"));
    }

    public String lessonOfPlay(String playId) {
        return plays.findById(playId).map(quest.server.content.Entities.PlayEntity::getLessonId)
                .orElseThrow(() -> ApiException.notFound("play"));
    }

    // ---------------------------------------------------------------- create and move

    @Transactional
    public AdminLesson create(Principals.User caller, TeacherDto.CreateTeacherLessonRequest body) {
        String subject = body.subject().trim().toLowerCase(Locale.ROOT);
        var section = scope.requireAssignment(caller, body.classId().trim(), subject);
        var source = source(body.source());
        requireSourceFlag(source);
        int practiceLength = body.practiceLength() == null ? 7 : body.practiceLength();
        var request = new CreateLessonRequest(Curriculum.valueOf(section.getCurriculum().toUpperCase(Locale.ROOT)),
                section.getGrade(), Subject.valueOf(subject.toUpperCase(Locale.ROOT)), kdate(TeacherWeekService.date(body.date())),
                body.notes(), practiceLength, source, body.title(), section.getId());
        return admin.create(request, caller);
    }

    /** Moving a card in the week grid. Only while it is unpublished — otherwise children have already played it. */
    @Transactional
    public AdminLesson move(Principals.User caller, String id, String date) {
        var lesson = require(caller, id);
        if (LessonState.status(lesson) == LessonStatus.PUBLISHED)
            throw ApiException.conflict("Unpublish the lesson before moving it to another day.");
        lesson.setDate(TeacherWeekService.date(date)); lesson.setUpdatedAt(Instant.now());
        return admin.toAdmin(lessons.save(lesson), true);
    }

    /** Draft or error only — a lesson that is ready or published is kept until she unpublishes and empties it. */
    @Transactional
    public void delete(Principals.User caller, String id) {
        var lesson = require(caller, id);
        var status = LessonState.status(lesson);
        if (status != LessonStatus.DRAFT && status != LessonStatus.ERROR)
            throw ApiException.conflict("Only a draft or a failed lesson can be deleted — unpublish it first if it is live.");
        admin.delete(id);
    }

    // ---------------------------------------------------------------- copy

    /**
     * A full copy of a made lesson into another class of the same grade and subject she teaches: plays, stops with
     * ids of their own, skills, page images and the parent panel.
     *
     * <p><strong>Why the ids are rewritten.</strong> Every id a lesson owns starts with the first eight characters of
     * its own id — stops, page images and skills alike ({@link StopIds}) — and an `Attempt` is stored against a stop
     * id. If the copy shared them, a child of 1B answering "the" stop would land in 1A's results. {@link
     * StopIds#reprefix} moves exactly those ids and nothing else, so the two classes' results are independent from
     * the first attempt, which `TeacherLessonsTest` asserts by playing one and reading the other.
     */
    @Transactional
    public AdminLesson copy(Principals.User caller, String id, String targetClassId) {
        var source = require(caller, id);
        var target = scope.requireAssignment(caller, targetClassId.trim(), source.getSubject());
        if (target.getId().equals(source.getClassId())) throw ApiException.badRequest("The lesson is already in that class.");
        requireSibling(source, target);
        var status = LessonState.status(source);
        if (status != LessonStatus.REVIEW && status != LessonStatus.PUBLISHED)
            throw ApiException.badRequest("Finish the lesson before copying it into another class.");
        return admin.toAdmin(copyInto(caller, source, target), true);
    }

    private LessonEntity copyInto(Principals.User caller, LessonEntity source, ClassEntity target) {
        var copy = new LessonEntity();
        copy.setId(UUID.randomUUID().toString());
        copy.setSchoolId(source.getSchoolId()); copy.setCourseId(source.getCourseId()); copy.setClassId(target.getId());
        copy.setSubject(source.getSubject()); copy.setDate(source.getDate()); copy.setStatus("review"); copy.setVersion(0);
        copy.setTitle(source.getTitle()); copy.setNotes(source.getNotes()); copy.setPracticeLength(source.getPracticeLength());
        // The hash travels with the copy so the editor's cache badge still reads "Analyzed before"; the copy spent
        // nothing, and what the original spent is what the copy saved.
        copy.setSourceHash(source.getSourceHash()); copy.setSource(source.getSource()); copy.setType(source.getType());
        // V8: every copy hangs off the same root, so "the copy of this lesson in 1B" is an exact question however
        // many times it is re-published and whether the publish was driven from the original or from a copy.
        copy.setCopiedFromLessonId(source.lineageRoot());
        copy.setAnalysisCacheHit(true);                          // a copy never called a model: the badge is true
        copy.setTokenUsage(0); copy.setTokensSaved(source.getTokenUsage() + source.getTokensSaved());
        copy.setTeacherId(scope.isTeacher(caller) ? caller.userId() : source.getTeacherId());
        copy.setCreatedBy(caller == null ? null : caller.email());
        copy.setCreatedAt(Instant.now()); copy.setUpdatedAt(Instant.now());
        lessons.save(copy);

        for (var skill : skills.findByLessonIdOrderByPosition(source.getId())) {
            var c = new SkillEntity();
            c.setId(reId(skill.getId(), source.getId(), copy.getId())); c.setLessonId(copy.getId());
            c.setName(skill.getName()); c.setSubject(skill.getSubject()); c.setMethod(skill.getMethod());
            c.setExamplesJson(skill.getExamplesJson()); c.setSlideNumbersJson(skill.getSlideNumbersJson());
            c.setConfidence(skill.getConfidence()); c.setUnsureJson(skill.getUnsureJson());
            c.setConfirmed(skill.isConfirmed()); c.setPosition(skill.getPosition());
            skills.save(c);
        }
        // The rows are copied, the bucket objects are not: both lessons point at the same stored page. Neither can be
        // deleted while the other exists — `delete` refuses anything but a draft or a failed lesson, and a copy is
        // made only from a lesson that is already ready or published.
        for (var image : pageImages.findByLessonIdOrderByPageNumber(source.getId())) {
            var c = new PageImageEntity();
            c.setId(StopIds.pageImageId(copy.getId(), image.getPageNumber())); c.setLessonId(copy.getId());
            c.setPageNumber(image.getPageNumber()); c.setStoragePath(image.getStoragePath());
            c.setWidth(image.getWidth()); c.setHeight(image.getHeight()); c.setDescription(image.getDescription());
            pageImages.save(c);
        }
        for (var play : plays.findByLessonIdOrderByLevelAscVariantAsc(source.getId())) {
            var tree = (ObjectNode) json.tree(play.getPlayJson());
            StopIds.reprefix(tree, source.getId(), copy.getId());
            tree.put("id", copy.getId() + ":" + play.getLevel() + ":" + play.getVariant());
            store.savePlay(copy.getId(), json.decodeShared(tree.toString(), Play.Companion.serializer()), play.getPromptVersion(), play.getSeed());
        }
        panels.findById(source.getId()).ifPresent(panel -> {
            var tree = (ObjectNode) json.tree(panel.getPanelJson());
            StopIds.reprefix(tree, source.getId(), copy.getId());
            store.savePanel(copy.getId(), json.decodeShared(tree.toString(), ParentPanel.Companion.serializer()));
        });
        return copy;
    }

    // ---------------------------------------------------------------- publish

    /**
     * §8's publish sheet: the complete set of classes the lesson should be live in. The lesson's own class is not
     * implied — naming it publishes it, leaving it out publishes only the copies — because "publish" from a card in
     * 1A's row and "publish to 1A and 1B" are the same request with different lists, and guessing would make one of
     * them wrong.
     *
     * <p>A re-publish must bump the version of <em>this lesson's</em> copy in each class rather than leave a second
     * card behind — and must never touch anything else. The copy is found by lineage (V8's `copied_from_lesson_id`),
     * not by day and subject: a class holding an unrelated draft of its own on the same day would otherwise be
     * published live by a teacher who only asked to share her own lesson.
     */
    @Transactional
    public List<TeacherDto.PublishedCopy> publish(Principals.User caller, String id, List<String> classIds) {
        var lesson = require(caller, id);
        if (classIds == null || classIds.isEmpty()) throw ApiException.badRequest("Name at least one class to publish into.");
        var results = new ArrayList<TeacherDto.PublishedCopy>();
        for (String raw : new LinkedHashSet<>(classIds)) {
            String classId = raw == null ? "" : raw.trim();
            String targetId;
            if (classId.equals(lesson.getClassId())) targetId = lesson.getId();
            else {
                var target = scope.requireAssignment(caller, classId, lesson.getSubject());
                requireSibling(lesson, target);
                targetId = copyIn(target.getId(), lesson).orElseGet(() -> copyInto(caller, lesson, target).getId());
            }
            results.add(new TeacherDto.PublishedCopy(classId, targetId, republish(targetId).getVersion()));
        }
        return List.copyOf(results);
    }

    public AdminLesson unpublish(Principals.User caller, String id) { return admin.unpublish(require(caller, id).getId()); }

    /** Publishing something that is already published is a re-publish: it goes back to review and up a version. */
    private AdminLesson republish(String lessonId) {
        if (LessonState.status(admin.get(lessonId)) == LessonStatus.PUBLISHED) admin.unpublish(lessonId);
        return admin.publish(lessonId);
    }

    /** This lesson's own member of that class — itself, or a copy of its root — and nothing else's. */
    private java.util.Optional<String> copyIn(String classId, LessonEntity lesson) {
        return lessons.findLineageIn(classId, lesson.lineageRoot()).stream().map(LessonEntity::getId).findFirst();
    }

    // ---------------------------------------------------------------- rules

    /** A copy only ever lands in a class of the same course: the plays were written for that grade's curriculum. */
    private static void requireSibling(LessonEntity lesson, ClassEntity target) {
        var course = Course.Companion.parse(lesson.getCourseId());
        if (target.getGrade() != course.getGrade() || !target.getCurriculum().equalsIgnoreCase(course.getCurriculum().name()))
            throw ApiException.forbidden("A lesson can only go into another class of the same grade and curriculum.");
    }

    /**
     * §4 of the schools prompt: a source a school has switched off is not offered and not accepted. The key depends
     * on the body, which is why it is not a `@FeatureFlag` on the route — and it answers 404 rather than 403, the
     * same silence the interceptor gives, so a school cannot tell a switched-off source from one that never existed.
     */
    private void requireSourceFlag(LessonSource source) {
        String key = switch (source) {
            case PDF -> FlagKeys.LESSONS_PDF;
            case SLIDES -> FlagKeys.LESSONS_SLIDES;
            case IMAGES -> FlagKeys.LESSONS_IMAGES;
            case MANUAL -> FlagKeys.LESSONS_MANUAL;
        };
        if (!flags.isOn(key))
            throw ApiException.notFound("lesson source");
    }

    private static LessonSource source(String raw) {
        try { return LessonSource.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("`" + raw + "` is not a lesson source (pdf, slides, images, manual)."); }
    }

    /** A skill or image id carrying the source lesson's prefix, moved onto the copy's. */
    private static String reId(String id, String fromLessonId, String toLessonId) {
        String from = StopIds.prefix8(fromLessonId) + ":";
        return id.startsWith(from) ? StopIds.prefix8(toLessonId) + ":" + id.substring(from.length()) : StopIds.prefix8(toLessonId) + ":" + id;
    }

    static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }
}
