package quest.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import quest.api.validation.SchemaValidator;
import quest.api.validation.Schemas;
import quest.api.validation.ValidationResult;
import quest.server.ai.LlmClient;
import quest.server.ai.Prompts;
import quest.server.api.dto.ApiError;
import quest.server.api.dto.Skills;
import quest.server.config.Json;
import quest.server.domain.LessonEntity;
import quest.server.domain.UploadEntity;
import quest.server.files.FileStore;
import quest.server.files.SlideConverter;

/** Prompt A: slides (+ typed task) → validated skill list. */
@Service
public class SkillExtractionService {
    private static final Logger log = LoggerFactory.getLogger(SkillExtractionService.class);
    private static final int MAX_PDF_PAGES = 100;
    private static final int MAX_IMAGES = 40;

    private final LlmClient llm;
    private final FileStore files;
    private final Json json;
    private final String systemA = Prompts.systemA(Schemas.INSTANCE.getSkillExtraction());

    public SkillExtractionService(LlmClient llm, FileStore files, Json json) { this.llm = llm; this.files = files; this.json = json; }

    public Skills.SkillExtraction extract(LessonEntity lesson, List<UploadEntity> uploads) throws ExtractionException {
        List<LlmClient.Block> blocks = new ArrayList<>();
        int slideCount = 0;
        for (UploadEntity u : uploads) {
            byte[] bytes;
            try { bytes = files.get(u.getStorageKey()); } catch (IOException e) { throw new ExtractionException(ApiError.UNREADABLE_FILE, "A file could not be read. Please upload it again.", e); }
            try {
                if ("application/pdf".equals(u.getMimeType())) {
                    int pages = SlideConverter.pdfPageCount(bytes);
                    if (pages > MAX_PDF_PAGES) throw new ExtractionException(ApiError.TOO_LARGE, "That PDF has " + pages + " pages. Please upload only today's slides.", null);
                    blocks.add(new LlmClient.Block.Pdf(bytes, "slides-" + (slideCount + 1)));
                    slideCount += pages;
                } else if (SlideConverter.PPTX.equals(u.getMimeType()) || u.getFileName().toLowerCase().endsWith(".pptx")) {
                    for (byte[] png : SlideConverter.pptxToPngs(bytes)) { blocks.add(new LlmClient.Block.Image(png, "image/png")); slideCount++; }
                } else if (u.getMimeType().startsWith("image/")) {
                    blocks.add(new LlmClient.Block.Image(bytes, u.getMimeType().equals("image/heic") ? "image/jpeg" : u.getMimeType()));
                    slideCount++;
                } else {
                    throw new ExtractionException(ApiError.UNREADABLE_FILE, "Unsupported file type. Use PDF, PowerPoint or photos.", null);
                }
            } catch (IOException | RuntimeException e) {
                throw new ExtractionException(ApiError.UNREADABLE_FILE, "We could not read those slides. Try a clearer photo or a PDF.", e);
            }
            if (slideCount > MAX_IMAGES) throw new ExtractionException(ApiError.TOO_LARGE, "Too many slides. Please upload only today's lesson.", null);
        }
        if (blocks.isEmpty() && (lesson.getTypedTask() == null || lesson.getTypedTask().isBlank())) {
            throw new ExtractionException(ApiError.UNREADABLE_FILE, "Nothing to read: add slides or type the task.", null);
        }
        blocks.add(new LlmClient.Block.Text(Prompts.userA(lesson.getSubject(), lesson.getGrade(), lesson.getCurriculum(), slideCount, lesson.getTypedTask())));

        List<LlmClient.Turn> turns = new ArrayList<>();
        turns.add(new LlmClient.Turn("user", blocks));
        ValidationResult last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw;
            try { raw = llm.complete(systemA, turns); } catch (LlmClient.LlmException e) { throw new ExtractionException(ApiError.MODEL_FAILED, "The slides could not be read right now. Please try again in a minute.", e); }
            String cleaned = JsonText.stripFences(raw);
            if (cleaned.contains("\"skills\": []") || cleaned.contains("\"skills\":[]")) {
                throw new ExtractionException(ApiError.NO_TEACHING_CONTENT, "We could not find a skill being taught in those slides. Try the slides with the lesson, or type the task.", null);
            }
            last = SchemaValidator.INSTANCE.validateSkillExtractionJson(cleaned);
            if (last.isValid()) {
                log.info("extract ok lesson={} attempt={}", lesson.getId(), attempt + 1);
                return json.read(cleaned, Skills.SkillExtraction.class);
            }
            log.warn("extract invalid lesson={} attempt={} errors={}", lesson.getId(), attempt + 1, last.getErrors());
            turns.add(new LlmClient.Turn("assistant", List.of(new LlmClient.Block.Text(raw))));
            turns.add(new LlmClient.Turn("user", List.of(new LlmClient.Block.Text(Prompts.retry(last.getErrors())))));
        }
        throw new ExtractionException(ApiError.MODEL_FAILED, "The slides were read but the result was not usable. Please try again.", null);
    }

    public static class ExtractionException extends Exception {
        public final String code;
        public ExtractionException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }
}
