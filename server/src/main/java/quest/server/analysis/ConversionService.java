package quest.server.analysis;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import quest.api.dto.ApiError;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SourceFileEntity;
import quest.server.content.LessonRepository;
import quest.server.content.SourceFileRepository;
import quest.server.files.FileStore;

/**
 * CR4 §4: an upload becomes Markdown on our own machines, and the model never sees the original binary.
 *
 * <p>Documents (`.pdf .pptx .ppt .docx .doc .xlsx .csv`) go through `@firecrawl/anydoc`; images (`.jpg .png`) and
 * the pages of a PDF with no text layer go through Tesseract (`eng+ara`), page by page under a `## Page N` heading.
 * The result is written next to the original in the bucket as `<storagePath>.md` and the file's row records which
 * way it came (`convert_method`), how long it is and, when it failed, which of anydoc's three refusals it was —
 * `encrypted`, `unsupported`, `malformed` — so the teacher is told what to do rather than "something went wrong".
 *
 * <p><strong>Two files with the same bytes convert once — inside one school.</strong> A `file_hash` that is already
 * `ready` on a lesson of the <em>same</em> school has its Markdown copied rather than reconverted. Two conditions,
 * both of them tenancy rather than thrift. The school is checked against the lesson each row hangs off, because a
 * hash is not a tenant boundary: the same worksheet downloaded by two schools would otherwise let B read text
 * produced inside A. And method `text` is never reused, because that is not a derivation of these bytes at all — it
 * is prose a teacher typed, which may say anything and belongs to her lesson. The copy is a real one: uploads are
 * swept after 24 hours, and pointing two rows at one path would make the second lose its text when the first goes.
 *
 * <p><strong>The extraction itself is {@link DocumentText}'s</strong> since feature 26, binaries, timeouts,
 * built-in fallback and all — unchanged, and now shared with the leaders' document reader, which has no lesson and
 * stores no file. What stays here is everything that makes a conversion a *lesson's*: the `source_files` row and its
 * terminal state, the per-school reuse above, and the Markdown written beside the original in the bucket.
 */
@Service
public class ConversionService {
    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    /** Output another file may inherit. `text` is the teacher's own prose and is hers alone. */
    static final Set<String> REUSABLE_METHODS = Set.of("anydoc", "ocr");

    private final SourceFileRepository sourceFiles; private final LessonRepository lessons; private final FileStore files; private final DocumentText text;
    private final int maxChars;

    public ConversionService(SourceFileRepository sourceFiles, LessonRepository lessons, FileStore files, DocumentText text,
                             @Value("${quest.pipeline.convert.max-markdown-chars:400000}") int maxChars) {
        this.sourceFiles = sourceFiles; this.lessons = lessons; this.files = files; this.text = text;
        this.maxChars = maxChars;
    }

    /** What one conversion produced: the Markdown, the way it was produced, and whether another file had it already. */
    public record Converted(String markdown, String method, boolean cacheHit) {}

    // ---------------------------------------------------------------- the Convert step

    /**
     * Converts one source file, or returns without a process when it is already `ready`. Always leaves the row in a
     * terminal state: `ready`, or `error` with the mapped code and a throw, so {@link LessonSteps#run} fails the step
     * with a message the teacher can act on. Runs on the pipeline thread — never on a request thread — because OCR
     * is measured in tens of seconds per page.
     */
    public Converted convert(SourceFileEntity f) {
        if (isReady(f)) return new Converted(null, f.getConvertMethod(), true);
        boolean forceOcr = ocrRequested(f);
        return settle(f, () -> {
            mark(f, "converting", forceOcr ? "ocr" : null);
            Converted c;
            if (forceOcr) c = ocrOnly(f);
            else {
                c = reuse(f);
                if (c == null) c = produce(f, extensionOf(f));
                else log.info("markdown reused for {} (the same bytes, already converted in this school)", f.getFileName());
            }
            store(f, c);
            return c;
        });
    }

    /**
     * The teacher asked for OCR on a file anydoc refused. Nothing is converted here: the row is parked at
     * `converting` with method `ocr`, which is what {@link #convert} reads as "she asked for the pictures to be
     * read", and the pipeline job that follows does the work off the request thread. A crash in between leaves the
     * same request standing, so the retry does the right thing rather than silently falling back to anydoc.
     */
    public void requestOcr(SourceFileEntity f) {
        f.setConvertStatus("converting"); f.setConvertMethod("ocr"); f.setConvertErrorCode(null);
        f.setMarkdownPath(null); f.setMarkdownChars(null);
        sourceFiles.save(f);
    }

    /** The other fallback: the teacher pasted the text herself, and it is stored exactly as she wrote it. */
    public Converted acceptMarkdown(SourceFileEntity f, String markdown) {
        if (markdown == null || markdown.isBlank()) throw ApiException.badRequest("Paste the lesson's text first.");
        return settle(f, () -> { var c = new Converted(markdown, "text", false); store(f, c); return c; });
    }

    static boolean ocrRequested(SourceFileEntity f) { return "converting".equals(f.getConvertStatus()) && "ocr".equals(f.getConvertMethod()); }

    private Converted ocrOnly(SourceFileEntity f) { return produced(text.ocrEverything(f.getFileName(), f.getMimeType(), blob(f).bytes())); }

    /**
     * No conversion may leave the row at `converting`. An {@link ApiException} carries its own code; anything else —
     * a bucket that refused the write, a renderer that threw — is `io`, because the teacher's next move is the same
     * either way and a row stuck mid-conversion is one the editor polls forever.
     */
    private Converted settle(SourceFileEntity f, java.util.function.Supplier<Converted> body) {
        try { return body.get(); }
        catch (ApiException e) { fail(f, e.error().code()); throw e; }
        catch (RuntimeException e) {
            log.error("conversion of {} crashed", f.getFileName(), e);
            fail(f, ApiError.IO);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, e.toString());
        }
    }

    /** The Markdown Prompt A reads, or empty when this file has not been converted (or the bucket swept it). */
    public Optional<String> markdown(SourceFileEntity f) {
        if (f.getMarkdownPath() == null) return Optional.empty();
        return files.get(f.getMarkdownPath()).map(b -> new String(b.bytes(), StandardCharsets.UTF_8));
    }

    /** Back to `pending` — the files were replaced, so whatever was converted from the old ones is no longer theirs. */
    public void reset(SourceFileEntity f) { f.clearMarkdown(); sourceFiles.save(f); }

    // ---------------------------------------------------------------- doing the work

    private boolean isReady(SourceFileEntity f) {
        return "ready".equals(f.getConvertStatus()) && f.getMarkdownPath() != null && files.get(f.getMarkdownPath()).isPresent();
    }

    private Converted reuse(SourceFileEntity f) {
        String school = schoolOf(f.getLessonId());
        if (school == null) return null;   // a lesson with no school reuses nothing: there is no boundary to check against
        for (var other : sourceFiles.findByFileHash(f.getFileHash())) {
            if (other.getId().equals(f.getId()) || !"ready".equals(other.getConvertStatus()) || other.getMarkdownPath() == null) continue;
            if (!REUSABLE_METHODS.contains(other.getConvertMethod())) continue;
            if (!school.equals(schoolOf(other.getLessonId()))) continue;
            var blob = files.get(other.getMarkdownPath()).orElse(null);
            if (blob != null) return new Converted(new String(blob.bytes(), StandardCharsets.UTF_8), other.getConvertMethod(), true);
        }
        return null;
    }

    /**
     * `findById`, deliberately: Hibernate's `school` filter does not apply to it, and the pipeline thread that calls
     * this holds no request scope to set it from. The boundary is checked here, in the open, rather than left to a
     * filter that may or may not be enabled.
     */
    private String schoolOf(String lessonId) { return lessons.findById(lessonId).map(LessonEntity::getSchoolId).orElse(null); }

    /** The file's own text, from {@link DocumentText}; which route it takes is its type's business, not a lesson's. */
    private Converted produce(SourceFileEntity f, String type) {
        return produced(text.extract(f.getFileName(), f.getMimeType(), type, blob(f).bytes()));
    }

    private static Converted produced(DocumentText.Extracted e) { return new Converted(e.markdown(), e.method(), false); }

    // ---------------------------------------------------------------- storing the result

    private void store(SourceFileEntity f, Converted c) {
        String md = c.markdown() == null ? "" : c.markdown().strip();
        if (md.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "anydoc".equals(c.method()) ? ApiError.NEEDS_OCR : ApiError.OCR_FAILED, "no text came out of " + f.getFileName());
        if (md.length() > maxChars) {
            log.warn("markdown for {} truncated from {} to {} characters", f.getFileName(), md.length(), maxChars);
            md = md.substring(0, maxChars) + "\n\n_(truncated at " + maxChars + " characters)_";
        }
        String path = f.getStoragePath() + ".md";
        files.put(path, md.getBytes(StandardCharsets.UTF_8), "text/markdown");
        f.setMarkdownPath(path); f.setMarkdownChars(md.length()); f.setConvertMethod(c.method());
        f.setConvertStatus("ready"); f.setConvertErrorCode(null);
        sourceFiles.save(f);
    }

    private void mark(SourceFileEntity f, String status, String method) { f.setConvertStatus(status); f.setConvertMethod(method); f.setConvertErrorCode(null); sourceFiles.save(f); }

    private void fail(SourceFileEntity f, String code) {
        f.setConvertStatus("error"); f.setConvertErrorCode(code); f.setConvertMethod(null); f.setMarkdownPath(null); f.setMarkdownChars(null);
        sourceFiles.save(f);
    }

    private FileStore.Blob blob(SourceFileEntity f) {
        return files.get(f.getStoragePath()).orElseThrow(() -> new ApiException(HttpStatus.GONE, ApiError.UNREADABLE_FILE, "The uploaded file has expired (files are kept 24 hours). Upload it again."));
    }

    /** The file's type: its extension, or what {@link SlideProcessor} called it when the name carries none. */
    static String extensionOf(SourceFileEntity f) {
        String name = f.getFileName() == null ? "" : f.getFileName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
        if (!ext.isBlank()) return ext;
        return switch (f.getKind() == null ? "" : f.getKind()) { case "pdf" -> "pdf"; case "pptx" -> "pptx"; case "image" -> "png"; default -> ext; };
    }
}
