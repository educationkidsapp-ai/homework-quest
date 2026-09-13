package quest.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import quest.server.api.ApiException;
import quest.server.api.dto.ApiError;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Questions;
import quest.server.api.dto.Requests;
import quest.server.api.dto.Skills;
import quest.server.config.Json;
import quest.server.domain.LessonEntity;
import quest.server.domain.SkillEntity;
import quest.server.domain.UploadEntity;
import quest.server.files.FileStore;
import quest.server.repository.*;

/** Lesson lifecycle: create → (pipeline) → needs_confirmation → confirm → (pipeline) → ready. */
@Service
public class LessonService {
    private static final Logger log = LoggerFactory.getLogger(LessonService.class);
    private final LessonRepository lessons;
    private final UploadRepository uploads;
    private final SkillRepository skills;
    private final QuestionSetStore sets;
    private final FileStore files;
    private final Json json;
    private final LessonPipeline pipeline;

    public LessonService(LessonRepository lessons, UploadRepository uploads, SkillRepository skills, QuestionSetStore sets,
                         FileStore files, Json json, LessonPipeline pipeline) {
        this.lessons = lessons; this.uploads = uploads; this.skills = skills; this.sets = sets; this.files = files; this.json = json; this.pipeline = pipeline;
    }

    public record UploadPart(String fileName, String mimeType, byte[] bytes) {}

    @Transactional
    public Requests.LessonJob create(Requests.CreateLessonRequest req, List<UploadPart> parts) {
        if (parts.isEmpty() && (req.typedTask() == null || req.typedTask().isBlank())) throw ApiException.badRequest("Add at least one file or a typed task.");
        LessonEntity lesson = new LessonEntity();
        lesson.setId(UUID.randomUUID().toString());
        lesson.setDate(req.date()); lesson.setSubject(req.subject().wire()); lesson.setGrade(req.grade()); lesson.setCurriculum(req.curriculum());
        lesson.setPracticeLength(req.practiceLength()); lesson.setStatus(Enums.Status.UPLOADING.wire()); lesson.setTypedTask(req.typedTask());
        lesson.setSourceFileNames(json.write(parts.stream().map(UploadPart::fileName).toList()));
        lesson.setCreatedAt(Instant.now()); lesson.setUpdatedAt(Instant.now());
        lessons.save(lesson);
        for (UploadPart p : parts) {
            UploadEntity u = new UploadEntity();
            u.setId(UUID.randomUUID().toString()); u.setLessonId(lesson.getId()); u.setFileName(p.fileName()); u.setMimeType(p.mimeType());
            u.setSizeBytes(p.bytes().length); u.setCreatedAt(Instant.now());
            String key = lesson.getId() + "/" + u.getId() + extension(p.fileName());
            try { u.setStorageKey(files.put(key, p.bytes(), p.mimeType())); } catch (IOException e) { throw new IllegalStateException("upload store failed", e); }
            uploads.save(u);
        }
        log.info("lesson created id={} files={} typed={}", lesson.getId(), parts.size(), lesson.getTypedTask() != null);
        afterCommit(() -> pipeline.extract(lesson.getId()));
        return toJob(lesson);
    }

    @Transactional(readOnly = true)
    public Requests.LessonJob get(String id) {
        return toJob(lessons.findById(id).orElseThrow(() -> ApiException.notFound("lesson")));
    }

    @Transactional
    public Requests.LessonJob confirm(String id, Requests.ConfirmSkillsRequest req) {
        LessonEntity lesson = lessons.findById(id).orElseThrow(() -> ApiException.notFound("lesson"));
        Enums.Status status = Enums.Status.from(lesson.getStatus());
        if (status != Enums.Status.NEEDS_CONFIRMATION && status != Enums.Status.ERROR && status != Enums.Status.READY) {
            throw ApiException.badRequest("The lesson is still being read.");
        }
        List<SkillEntity> existing = skills.findByLessonIdOrderByPosition(id);
        for (SkillEntity s : existing) s.setConfirmed(false);
        int position = existing.size();
        for (Requests.ConfirmedSkill c : req.skills()) {
            SkillEntity target = c.id() == null ? null : existing.stream().filter(s -> s.getId().equals(c.id())).findFirst().orElse(null);
            if (target == null) {
                target = new SkillEntity();
                target.setId(id.substring(0, 8) + "-" + slug(c.name()) + "-" + Integer.toHexString(c.name().hashCode() & 0xffff));
                target.setLessonId(id); target.setSubject(c.subject().wire()); target.setMethod(c.method() == null ? "as written by the parent" : c.method());
                target.setExamplesJson(json.write(List.of(c.name()))); target.setConfidence(1.0); target.setPosition(position++);
            }
            target.setName(c.name());
            target.setConfirmed(true);
            skills.save(target);
        }
        lesson.setPracticeLength(req.practiceLength());
        lesson.setStatus(Enums.Status.GENERATING.wire()); lesson.setErrorCode(null); lesson.setErrorMessage(null); lesson.setUpdatedAt(Instant.now());
        lessons.save(lesson);
        log.info("lesson confirmed id={} skills={}", id, req.skills().size());
        afterCommit(() -> pipeline.generate(id));
        return toJob(lesson);
    }

    @Transactional
    public int deleteFiles(String id) {
        lessons.findById(id).orElseThrow(() -> ApiException.notFound("lesson"));
        int n = 0;
        for (UploadEntity u : uploads.findByLessonIdAndDeletedAtIsNull(id)) {
            try { files.delete(u.getStorageKey()); } catch (IOException e) { log.warn("delete failed upload={}", u.getId()); }
            u.setDeletedAt(Instant.now());
            uploads.save(u);
            n++;
        }
        log.info("files deleted lesson={} count={}", id, n);
        return n;
    }

    /** Runs the (async, proxied) pipeline step only after this transaction commits, so the worker sees the rows. */
    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { r.run(); } });
        } else r.run();
    }

    Requests.LessonJob toJob(LessonEntity lesson) {
        Enums.Status status = Enums.Status.from(lesson.getStatus());
        List<Skills.ExtractedSkill> skillDtos = new ArrayList<>();
        List<Questions.QuestionSet> setDtos = new ArrayList<>();
        if (status != Enums.Status.UPLOADING && status != Enums.Status.READING) {
            for (SkillEntity s : skills.findByLessonIdOrderByPosition(lesson.getId())) {
                if (status == Enums.Status.READY && !s.isConfirmed()) continue;
                skillDtos.add(new Skills.ExtractedSkill(s.getId(), s.getName(), Enums.Subject.from(s.getSubject()), s.getMethod(),
                        json.strings(s.getExamplesJson()), json.read(s.getSlideNumbersJson(), new TypeReference<List<Integer>>() {}), s.getConfidence(),
                        s.getUnsureJson() == null ? null : json.read(s.getUnsureJson(), Skills.Unsure.class)));
                if (status == Enums.Status.READY) {
                    List<Questions.QuestionSet> all = sets.forSkill(s.getId());
                    all.stream().filter(q -> q.mode() == Enums.Mode.NORMAL).findFirst().or(() -> all.stream().findFirst()).ifPresent(setDtos::add);
                }
            }
        }
        ApiError error = lesson.getErrorCode() == null ? null : new ApiError(lesson.getErrorCode(), lesson.getErrorMessage());
        return new Requests.LessonJob(lesson.getId(), status, Enums.Subject.from(lesson.getSubject()), lesson.getDate(), skillDtos, setDtos, error, json.strings(lesson.getSourceFileNames()));
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
    }

    static String slug(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "skill" : slug.substring(0, Math.min(30, slug.length()));
    }
}
