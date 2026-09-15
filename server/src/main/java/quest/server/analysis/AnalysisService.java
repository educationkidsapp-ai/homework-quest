package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.CacheKeys;
import quest.api.dto.Curriculum;
import quest.api.dto.SourceAnalysis;
import quest.api.dto.Subject;
import quest.api.validation.SchemaValidator;
import quest.api.validation.Sha256;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PageImageEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.Entities.SourceFileEntity;
import quest.server.content.LessonRepository;
import quest.server.content.PageImageRepository;
import quest.server.content.SkillRepository;
import quest.server.content.SourceFileRepository;
import quest.server.files.FileStore;

/**
 * Uploads (hash, store, render pages) and Prompt A. The permanent cache is checked first: the same slides for the same
 * course never cost a second model call.
 */
@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    public static final int MAX_IMAGES_PER_CALL = 20;

    private final LessonRepository lessons; private final SourceFileRepository sourceFiles; private final PageImageRepository pageImages; private final SkillRepository skills;
    private final AnalysisCacheRepository cache; private final FileStore files; private final SlideProcessor slides; private final LlmClient llm; private final Json json; private final LessonState state;

    public AnalysisService(LessonRepository lessons, SourceFileRepository sourceFiles, PageImageRepository pageImages, SkillRepository skills, AnalysisCacheRepository cache, FileStore files, SlideProcessor slides, LlmClient llm, Json json, LessonState state) {
        this.lessons = lessons; this.sourceFiles = sourceFiles; this.pageImages = pageImages; this.skills = skills; this.cache = cache; this.files = files; this.slides = slides; this.llm = llm; this.json = json; this.state = state;
    }

    public record Upload(String fileName, String mimeType, byte[] bytes) {}

    /** Stores the files, renders their pages and computes the lesson's source hash. Cheap, synchronous. */
    @Transactional
    public void upload(LessonEntity lesson, List<Upload> uploads) {
        if (uploads.isEmpty()) throw ApiException.badRequest("No files.");
        if (LessonState.status(lesson) == quest.api.dto.LessonStatus.PUBLISHED) throw ApiException.badRequest("Unpublish the lesson before changing its files.");
        int pageOffset = pageImages.findByLessonIdOrderByPageNumber(lesson.getId()).size();
        for (var u : uploads) {
            var source = slides.process(u.fileName(), u.mimeType(), u.bytes());
            var hash = Sha256.INSTANCE.hex(u.bytes());
            var id = UUID.randomUUID().toString();
            var stored = files.put("uploads/" + lesson.getId() + "/" + id + "/" + safe(u.fileName()), u.bytes(), u.mimeType() == null ? "application/octet-stream" : u.mimeType());
            var e = new SourceFileEntity();
            e.setId(id); e.setLessonId(lesson.getId()); e.setFileName(u.fileName()); e.setFileHash(hash); e.setKind(source.kind()); e.setMimeType(stored.mimeType());
            e.setPageCount(source.pages().size()); e.setStoragePath(stored.path()); e.setSizeBytes(stored.size()); e.setCreatedAt(Instant.now());
            sourceFiles.save(e);
            for (var p : source.pages()) {
                int number = pageOffset + p.number();
                var img = new PageImageEntity();
                img.setId(StopIds.pageImageId(lesson.getId(), number)); img.setLessonId(lesson.getId()); img.setPageNumber(number);
                img.setStoragePath(files.put("pages/" + lesson.getId() + "/" + number + ".png", p.png(), "image/png").path()); img.setWidth(p.width()); img.setHeight(p.height());
                pageImages.save(img);
            }
            pageOffset += source.pages().size();
        }
        var hash = sourceHash(lesson);
        lesson.setSourceHash(hash);
        boolean hit = cache.existsById(CacheKeys.INSTANCE.analysisKey(hash));
        for (var f : sourceFiles.findByLessonIdOrderByCreatedAt(lesson.getId())) if (f.getDeletedAt() == null) { f.setCacheHit(hit); sourceFiles.save(f); }
        lesson.setStatus("draft"); lesson.setUpdatedAt(Instant.now());
        lessons.save(lesson);
    }

    public String sourceHash(LessonEntity lesson) {
        var hashes = sourceFiles.findByLessonIdOrderByCreatedAt(lesson.getId()).stream().filter(f -> f.getDeletedAt() == null).map(SourceFileEntity::getFileHash).toList();
        return CacheKeys.INSTANCE.sourceHash(hashes, Curriculum.valueOf(lesson.getCourseId().split("/")[0].toUpperCase()), Integer.parseInt(lesson.getCourseId().split("/")[1]), Subject.valueOf(lesson.getSubject().toUpperCase()), lesson.getNotes());
    }

    /** Prompt A, cache first. Returns the analysis JSON and records usage on the lesson. Runs inside the async job. */
    public SourceAnalysis analyze(LessonEntity lesson) {
        var activeFiles = sourceFiles.findByLessonIdOrderByCreatedAt(lesson.getId()).stream().filter(f -> f.getDeletedAt() == null).toList();
        if (activeFiles.isEmpty()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "bad_request", "Upload the slides first.");
        String hash = sourceHash(lesson);
        String key = CacheKeys.INSTANCE.analysisKey(hash);
        var cached = cache.findById(key).orElse(null);
        String analysisJson;
        if (cached != null) {
            cached.setHits(cached.getHits() + 1); cache.save(cached);
            state.addUsage(lesson.getId(), 0, cached.getTokenUsage());
            analysisJson = cached.getAnalysisJson();
            log.info("analysis cache hit for lesson {} ({})", lesson.getId(), key);
        } else {
            analysisJson = runPromptA(lesson, activeFiles, hash, key);
        }
        var analysis = json.decodeShared(analysisJson, SourceAnalysis.Companion.serializer());
        applyAnalysis(lesson, analysis);
        return analysis;
    }

    /**
     * Prompt A on text the admin typed (manual lessons): the text is the single "page", cached under the hash of the
     * text + course like any upload. Confirms every extracted skill so generation can follow immediately.
     */
    public SourceAnalysis analyzeText(LessonEntity lesson, String text) {
        String hash = CacheKeys.INSTANCE.sourceHash(List.of(Sha256.INSTANCE.hex(text)), Curriculum.valueOf(lesson.getCourseId().split("/")[0].toUpperCase()), Integer.parseInt(lesson.getCourseId().split("/")[1]), Subject.valueOf(lesson.getSubject().toUpperCase()), lesson.getNotes());
        String key = CacheKeys.INSTANCE.analysisKey(hash);
        var cached = cache.findById(key).orElse(null);
        String analysisJson;
        if (cached != null) { cached.setHits(cached.getHits() + 1); cache.save(cached); state.addUsage(lesson.getId(), 0, cached.getTokenUsage()); analysisJson = cached.getAnalysisJson(); }
        else {
            var src = new SlideProcessor.Source("text", List.of(new SlideProcessor.Page(1, text, new byte[0], 0, 0)));
            analysisJson = promptA(lesson, src, List.of(), hash, key);
        }
        lesson.setSourceHash(hash); lessons.save(lesson);
        var analysis = json.decodeShared(analysisJson, SourceAnalysis.Companion.serializer());
        applyAnalysis(lesson, analysis);
        for (var s : skills.findByLessonIdOrderByPosition(lesson.getId())) { s.setConfirmed(true); s.setUnsureJson(null); skills.save(s); }
        return analysis;
    }

    private String runPromptA(LessonEntity lesson, List<SourceFileEntity> activeFiles, String hash, String key) {
        List<SlideProcessor.Page> pages = new ArrayList<>(); List<LlmClient.Attachment> attachments = new ArrayList<>();
        int offset = 0;
        for (var f : activeFiles) {
            var blob = files.get(f.getStoragePath()).orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.GONE, "unreadable_file", "The uploaded file has expired (files are kept 24 hours). Upload it again."));
            if (llm.acceptsPdf() && "pdf".equals(f.getKind())) attachments.add(new LlmClient.Attachment("application/pdf", blob.bytes(), f.getFileName()));
            var source = slides.process(f.getFileName(), f.getMimeType(), blob.bytes());
            for (var p : source.pages()) pages.add(new SlideProcessor.Page(offset + p.number(), p.text(), p.png(), p.width(), p.height()));
            offset += source.pages().size();
        }
        if (attachments.isEmpty()) for (var p : pages) { if (attachments.size() >= MAX_IMAGES_PER_CALL) break; attachments.add(new LlmClient.Attachment("image/png", p.png(), "page-" + p.number())); }
        return promptA(lesson, new SlideProcessor.Source("mixed", pages), attachments, hash, key);
    }

    private String promptA(LessonEntity lesson, SlideProcessor.Source src, List<LlmClient.Attachment> attachments, String hash, String key) {
        String[] course = lesson.getCourseId().split("/");
        String user = Prompts.userA(course[0], Integer.parseInt(course[1]), lesson.getSubject(), lesson.getNotes(), src.textDump(), !attachments.isEmpty());
        long used = 0; String text = null; List<String> errors = List.of();
        for (int attempt = 0; attempt < 2; attempt++) {
            String u = attempt == 0 ? user : user + "\n\nYour previous answer was rejected by the validator:\n- " + String.join("\n- ", errors) + "\nAnswer again with corrected JSON only.";
            LlmClient.Result r;
            try { r = llm.complete(Prompts.SYSTEM_A, u, attachments); }
            catch (LlmClient.LlmException e) { if (e.isTransient()) throw new LessonSteps.TransientFailure(e.getMessage(), e); throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "model_failed", e.getMessage()); }
            used += r.total();
            JsonNode probe = tryTree(r.text());
            if (probe != null && "no_teaching_content".equals(probe.path("error").asText(null))) { state.addUsage(lesson.getId(), used, 0); throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "no_teaching_content", "These slides don't contain anything to practise."); }
            String cleaned = LlmJson.cleanIllustrations(r.text());
            var result = SchemaValidator.INSTANCE.validateAnalysisJson(cleaned);
            if (result.getErrors().isEmpty()) { text = cleaned; break; }
            errors = result.getErrors(); log.warn("Prompt A attempt {} invalid: {}", attempt + 1, errors);
            LlmFailures.keep("A", r.text(), errors);
        }
        state.addUsage(lesson.getId(), used, 0);
        if (text == null) throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "model_failed", "The model's analysis didn't match the schema: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
        var e = new CacheEntities.AnalysisCacheEntity();
        e.setCacheKey(key); e.setSourceHash(hash); e.setCurriculum(course[0]); e.setGrade(Integer.parseInt(course[1])); e.setSubject(lesson.getSubject()); e.setPromptVersion(CacheKeys.PROMPT_A_VERSION);
        e.setAnalysisJson(text); e.setTokenUsage(used); e.setHits(0); e.setCreatedAt(Instant.now());
        cache.save(e);
        return text;
    }

    /** Skills rows (unconfirmed), lesson title, page descriptions. */
    @Transactional
    public void applyAnalysis(LessonEntity lesson, SourceAnalysis analysis) {
        var fresh = lessons.findById(lesson.getId()).orElseThrow();
        skills.deleteAll(skills.findByLessonIdOrderByPosition(fresh.getId()));
        int pos = 0;
        for (var s : analysis.getSkills()) {
            var e = new SkillEntity();
            e.setId(fresh.getId().substring(0, 8) + ":" + s.getId()); e.setLessonId(fresh.getId()); e.setName(s.getName()); e.setSubject(s.getSubject().name().toLowerCase()); e.setMethod(s.getMethod());
            e.setExamplesJson(json.write(s.getExamples())); e.setSlideNumbersJson(json.write(s.getSlideNumbers())); e.setConfidence(s.getConfidence());
            e.setUnsureJson(s.getUnsure() == null ? null : json.write(java.util.Map.of("candidates", s.getUnsure().getCandidates(), "question", s.getUnsure().getQuestion())));
            e.setConfirmed(false); e.setPosition(pos++);
            skills.save(e);
        }
        if (fresh.getTitle() == null || fresh.getTitle().isBlank()) fresh.setTitle(analysis.getTitle());
        var imgs = pageImages.findByLessonIdOrderByPageNumber(fresh.getId());
        for (var p : analysis.getPages()) imgs.stream().filter(i -> i.getPageNumber() == p.getNumber()).findFirst().ifPresent(i -> { i.setDescription(p.getPictureDescription()); pageImages.save(i); });
        fresh.setUpdatedAt(Instant.now());
        lessons.save(fresh);
    }

    private JsonNode tryTree(String s) { try { return json.tree(s); } catch (Exception e) { return null; } }
    private static String safe(String name) { return name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
