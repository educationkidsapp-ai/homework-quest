package quest.server.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 * <p><strong>No binaries, no pipeline?</strong> Only where that is declared, and the declaration is the profile
 * rather than an environment variable: the built-in fallback needs both
 * `quest.pipeline.convert.allow-builtin-fallback` and an active `h2` or `test` profile, so no value set on a QA or
 * production Cloud Run service can switch it on. Where it does apply, a missing binary falls back to the PDFBox/POI
 * text extraction {@link SlideProcessor} already does, recorded honestly as method `text`, and an e2e run needs
 * neither Node nor Tesseract. Everywhere else a missing binary is an operator error naming the variable to set.
 */
@Service
public class ConversionService {
    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    /** The seven document types §4 names; anything else is `unsupported` before a process is started. */
    static final Set<String> ANYDOC_TYPES = Set.of("pdf", "pptx", "ppt", "docx", "doc", "xlsx", "csv");
    static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png", "webp");
    /** `anydoc: pages 2, 3 of 10 need OCR` / `anydoc: page 1 of 1 needs OCR`. */
    private static final Pattern PAGES_NEEDING_OCR = Pattern.compile("pages?\\s+([0-9][0-9,\\s]*)\\s+of\\s+\\d+");

    /** Output another file may inherit. `text` is the teacher's own prose and is hers alone. */
    static final Set<String> REUSABLE_METHODS = Set.of("anydoc", "ocr");
    /** The only profiles in which a missing binary may fall back to PDFBox/POI instead of failing the step. */
    private static final Profiles FALLBACK_PROFILES = Profiles.of("h2", "test");

    private final SourceFileRepository sourceFiles; private final LessonRepository lessons; private final FileStore files; private final SlideProcessor slides; private final ProcessRunner runner;
    private final String anydocBin; private final String tesseractBin; private final Duration timeout; private final Duration pageTimeout; private final int maxChars; private final boolean builtinFallback;

    public ConversionService(SourceFileRepository sourceFiles, LessonRepository lessons, FileStore files, SlideProcessor slides, ProcessRunner runner,
                             @Value("${quest.pipeline.convert.anydoc-bin:anydoc}") String anydocBin,
                             @Value("${quest.pipeline.convert.tesseract-bin:tesseract}") String tesseractBin,
                             @Value("${quest.pipeline.convert.timeout-seconds:120}") long timeoutSeconds,
                             @Value("${quest.pipeline.convert.ocr-page-timeout-seconds:20}") long pageTimeoutSeconds,
                             @Value("${quest.pipeline.convert.max-markdown-chars:400000}") int maxChars,
                             @Value("${quest.pipeline.convert.allow-builtin-fallback:false}") boolean allowBuiltinFallback,
                             Environment env) {
        this.sourceFiles = sourceFiles; this.lessons = lessons; this.files = files; this.slides = slides; this.runner = runner;
        this.anydocBin = anydocBin; this.tesseractBin = tesseractBin; this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.pageTimeout = Duration.ofSeconds(pageTimeoutSeconds); this.maxChars = maxChars;
        // Both, never either: the property says what this deployment wants and the profile says what it is allowed
        // to want. `qa` and `prod` cannot turn it on however their environment is written.
        this.builtinFallback = allowBuiltinFallback && env.acceptsProfiles(FALLBACK_PROFILES);
    }

    /** What one conversion produced: the Markdown, the way it was produced, and whether another file had it already. */
    public record Converted(String markdown, String method, boolean cacheHit) {}

    /** Raised inside a conversion when the binary is not installed; caught here, never by a caller. */
    private static final class ToolMissing extends RuntimeException { ToolMissing(String m) { super(m); } }

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

    private Converted ocrOnly(SourceFileEntity f) {
        try { return new Converted(ocrPages(f.getFileName(), f.getMimeType(), blob(f).bytes(), List.of()), "ocr", false); }
        catch (ToolMissing e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.TOOL_MISSING, e.getMessage()); }
    }

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

    private Converted produce(SourceFileEntity f, String type) {
        byte[] bytes = blob(f).bytes();
        try {
            if (IMAGE_TYPES.contains(type)) return new Converted(ocrImage(type, bytes), "ocr", false);
            if (ANYDOC_TYPES.contains(type)) return anydoc(f, type, bytes);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.UNSUPPORTED, "a ." + type + " file");
        } catch (ToolMissing e) {
            if (!builtinFallback) throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.TOOL_MISSING, e.getMessage());
            log.warn("{} — using the built-in text extractor for {} (quest.pipeline.convert.allow-builtin-fallback)", e.getMessage(), f.getFileName());
            return new Converted(builtin(f, bytes), "text", false);
        }
    }

    private Converted anydoc(SourceFileEntity f, String type, byte[] bytes) {
        Path dir = temp("quest-anydoc");
        try {
            Path in = dir.resolve("source." + type); Files.write(in, bytes);
            Path out = dir.resolve("out.md");
            var command = new ArrayList<>(List.of(anydocBin, in.toString(), "-o", out.toString()));
            if ("csv".equals(type)) { command.add("--format"); command.add("csv"); }   // never `--ocr`: exit 3 tells us which pages need it
            var ran = launch(command, dir, anydocBin, "QUEST_ANYDOC_BIN", timeout);
            return switch (ran.exitCode()) {
                case 0 -> new Converted(Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8) : "", "anydoc", false);
                case 3 -> new Converted(ocrPages(f.getFileName(), f.getMimeType(), bytes, pagesNeedingOcr(ran.stderr())), "ocr", false);
                case 1 -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, codeOf(ran.stderr()), ran.firstErrorLine());
                default -> throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, "anydoc exited " + ran.exitCode() + ": " + ran.firstErrorLine());
            };
        } catch (IOException e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, e.getMessage());
        } finally { wipe(dir); }
    }

    /** One image straight through Tesseract — no page headings, because there is one page. */
    private String ocrImage(String type, byte[] bytes) {
        Path dir = temp("quest-ocr");
        try { Path in = dir.resolve("page." + type); Files.write(in, bytes); return tesseract(dir, in, pageTimeout); }
        catch (IOException e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, e.getMessage()); }
        finally { wipe(dir); }
    }

    /**
     * The scanned-PDF path: the pages {@link SlideProcessor} already renders for the child's player go through
     * Tesseract one by one. {@code wanted} is what anydoc's exit 3 named; empty means every page.
     *
     * <p>Two boxes, because one is not enough: {@code ocr-page-timeout-seconds} stops one unreadable page from
     * eating the whole budget, and {@code timeout-seconds} is the budget for the file, so a forty-page scan cannot
     * turn forty page boxes into forty times the wait. Running out is `io` — "press Retry", not "your file is bad".
     */
    private String ocrPages(String fileName, String mimeType, byte[] bytes, List<Integer> wanted) {
        List<SlideProcessor.Page> pages;
        try { pages = slides.process(fileName, mimeType, bytes).pages(); }
        catch (ApiException e) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.OCR_FAILED, e.error().message()); }
        var sb = new StringBuilder();
        Path dir = temp("quest-ocr");
        var deadline = java.time.Instant.now().plus(timeout);
        try {
            for (var p : pages) {
                if (!wanted.isEmpty() && !wanted.contains(p.number())) continue;
                Duration left = Duration.between(java.time.Instant.now(), deadline);
                if (!left.isPositive()) throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, ApiError.IO,
                        "reading " + fileName + " with OCR ran past its " + timeout.toSeconds() + "s");
                Path in = dir.resolve("page-" + p.number() + ".png"); Files.write(in, p.png());
                sb.append("## Page ").append(p.number()).append("\n\n").append(tesseract(dir, in, min(pageTimeout, left)).strip()).append("\n\n");
            }
        } catch (IOException e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, e.getMessage());
        } finally { wipe(dir); }
        return sb.toString();
    }

    private static Duration min(Duration a, Duration b) { return a.compareTo(b) <= 0 ? a : b; }

    private String tesseract(Path dir, Path image, Duration box) {
        var ran = launch(List.of(tesseractBin, image.toString(), "stdout", "-l", "eng+ara"), dir, tesseractBin, "QUEST_TESSERACT_BIN", box);
        if (ran.exitCode() != 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.OCR_FAILED, ran.firstErrorLine());
        return ran.stdout();
    }

    /** PDFBox / POI text extraction, headed per page the same way OCR is — the local e2e's way through. */
    private String builtin(SourceFileEntity f, byte[] bytes) {
        var sb = new StringBuilder();
        for (var p : slides.process(f.getFileName(), f.getMimeType(), bytes).pages())
            sb.append("## Page ").append(p.number()).append("\n\n").append(p.text().strip()).append("\n\n");
        return sb.toString();
    }

    private ProcessRunner.Ran launch(List<String> command, Path dir, String bin, String envVar, Duration box) {
        try { return runner.run(command, dir, box); }
        catch (ProcessRunner.Timeout e) { throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, ApiError.IO, e.getMessage()); }
        catch (IOException e) { throw new ToolMissing(bin + " could not be started — set " + envVar + " to its path or put it on PATH (" + e.getMessage() + ")"); }
    }

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

    // ---------------------------------------------------------------- reading what the tools said

    /** anydoc's exit-1 stderr, which names the refusal before the colon; anything unrecognised reads as malformed. */
    static String codeOf(String stderr) {
        String s = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (s.contains("encrypted")) return ApiError.ENCRYPTED;
        if (s.contains("unsupported input")) return ApiError.UNSUPPORTED;
        if (s.contains("io error")) return ApiError.IO;
        return ApiError.MALFORMED;
    }

    /** `page 1 of 1 needs OCR` → [1]; `all 5 pages need OCR` → [] (every page). */
    static List<Integer> pagesNeedingOcr(String stderr) {
        String s = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (s.contains("all ")) return List.of();
        Matcher m = PAGES_NEEDING_OCR.matcher(s);
        if (!m.find()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : m.group(1).split("[,\\s]+")) if (!part.isBlank()) out.add(Integer.parseInt(part));
        return out;
    }

    /** The file's type: its extension, or what {@link SlideProcessor} called it when the name carries none. */
    static String extensionOf(SourceFileEntity f) {
        String name = f.getFileName() == null ? "" : f.getFileName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
        if (!ext.isBlank()) return ext;
        return switch (f.getKind() == null ? "" : f.getKind()) { case "pdf" -> "pdf"; case "pptx" -> "pptx"; case "image" -> "png"; default -> ext; };
    }

    private static Path temp(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch (IOException e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, e.getMessage()); }
    }

    private static void wipe(Path dir) {
        try (var s = Files.walk(dir)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (IOException ignored) { /* a temp directory */ }
    }
}
