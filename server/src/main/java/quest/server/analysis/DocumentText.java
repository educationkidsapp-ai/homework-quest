package quest.server.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import quest.api.dto.ApiError;
import quest.server.config.ApiException;

/**
 * <strong>Bytes to Markdown, on our own machines.</strong> CR4 §4's extraction, lifted out of
 * {@link ConversionService} by feature 26 and otherwise unchanged: documents (`.pdf .pptx .ppt .docx .doc .xlsx
 * .csv`) go through `@firecrawl/anydoc`, images (`.jpg .png .webp`) and the pages of a PDF with no text layer go
 * through Tesseract (`eng+ara`), page by page under a `## Page N` heading.
 *
 * <p>It exists because there are now two readers and only one of them has a lesson. {@link ConversionService} still
 * owns everything that makes a conversion a <em>lesson's</em> conversion — the `source_files` row, its status and
 * error code, the per-school reuse of identical bytes, and the Markdown written beside the original in the bucket —
 * and delegates the part that is only ever "what does this file say" to here. Feature 26's
 * {@link quest.server.documents.DocumentReaderService} calls the same methods with a leader's circular, which belongs
 * to no lesson and is <strong>never stored at all</strong>.
 *
 * <p><strong>No binaries, no pipeline?</strong> Only where that is declared, and the declaration is the profile
 * rather than an environment variable: the built-in fallback needs both
 * `quest.pipeline.convert.allow-builtin-fallback` and an active `h2` or `test` profile, so no value set on a QA or
 * production Cloud Run service can switch it on. Where it does apply, a missing binary falls back to the PDFBox/POI
 * text extraction {@link SlideProcessor} already does, recorded honestly as method `text`, and an e2e run needs
 * neither Node nor Tesseract. Everywhere else a missing binary is an operator error naming the variable to set.
 *
 * <p><strong>Every temporary file is deleted in a `finally`.</strong> It always was — OCR renders forty PNGs for a
 * forty-page scan — and feature 26 turns that into a promise it tests: a circular is read from a temporary directory
 * that is gone before the answer is returned, whether the read succeeded or threw.
 */
@Component
public class DocumentText {
    private static final Logger log = LoggerFactory.getLogger(DocumentText.class);

    /** The document types §4 names; anything else is `unsupported` before a process is started. */
    public static final Set<String> ANYDOC_TYPES = Set.of("pdf", "pptx", "ppt", "docx", "doc", "pptm", "ppsx", "xlsx", "csv");
    public static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png", "webp");
    /** `anydoc: pages 2, 3 of 10 need OCR` / `anydoc: page 1 of 1 needs OCR`. */
    private static final Pattern PAGES_NEEDING_OCR = Pattern.compile("pages?\\s+([0-9][0-9,\\s]*)\\s+of\\s+\\d+");
    /** The only profiles in which a missing binary may fall back to PDFBox/POI instead of failing the read. */
    private static final Profiles FALLBACK_PROFILES = Profiles.of("h2", "test");

    private final SlideProcessor slides; private final ProcessRunner runner;
    private final String anydocBin; private final String tesseractBin; private final Duration timeout; private final Duration pageTimeout;
    private final boolean builtinFallback;

    public DocumentText(SlideProcessor slides, ProcessRunner runner,
                        @Value("${quest.pipeline.convert.anydoc-bin:anydoc}") String anydocBin,
                        @Value("${quest.pipeline.convert.tesseract-bin:tesseract}") String tesseractBin,
                        @Value("${quest.pipeline.convert.timeout-seconds:120}") long timeoutSeconds,
                        @Value("${quest.pipeline.convert.ocr-page-timeout-seconds:20}") long pageTimeoutSeconds,
                        @Value("${quest.pipeline.convert.allow-builtin-fallback:false}") boolean allowBuiltinFallback,
                        Environment env) {
        this.slides = slides; this.runner = runner;
        this.anydocBin = anydocBin; this.tesseractBin = tesseractBin;
        this.timeout = Duration.ofSeconds(timeoutSeconds); this.pageTimeout = Duration.ofSeconds(pageTimeoutSeconds);
        // Both, never either: the property says what this deployment wants and the profile says what it is allowed
        // to want. `qa` and `prod` cannot turn it on however their environment is written.
        this.builtinFallback = allowBuiltinFallback && env.acceptsProfiles(FALLBACK_PROFILES);
    }

    /** What one extraction produced: the Markdown and the way it was produced (`anydoc`, `ocr` or `text`). */
    public record Extracted(String markdown, String method) {}

    /** Raised inside an extraction when the binary is not installed; caught here, never by a caller. */
    private static final class ToolMissing extends RuntimeException { ToolMissing(String m) { super(m); } }

    /**
     * One file's text, by whichever route its type takes. {@code type} is the extension the caller decided on — the
     * lesson's from its file name, feature 26's from the first bytes — so this method never has to guess.
     *
     * @throws ApiException 422 `unsupported` for a type §4 does not name, 502 `tool_missing` when the binary is
     *         absent and this deployment may not fall back, and whatever {@link #anydocFirst} mapped otherwise
     */
    public Extracted extract(String fileName, String mimeType, String type, byte[] bytes) {
        try {
            if (IMAGE_TYPES.contains(type)) return new Extracted(ocrImage(type, bytes), "ocr");
            if (ANYDOC_TYPES.contains(type)) return anydocFirst(fileName, mimeType, type, bytes);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.UNSUPPORTED, "a ." + type + " file");
        } catch (ToolMissing e) {
            if (!builtinFallback) throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.TOOL_MISSING, e.getMessage());
            log.warn("{} — using the built-in text extractor for {} (quest.pipeline.convert.allow-builtin-fallback)", e.getMessage(), fileName);
            return new Extracted(builtin(fileName, mimeType, bytes), "text");
        }
    }

    /** Every page through Tesseract, whatever anydoc would have said — the teacher asked for the pictures to be read. */
    public Extracted ocrEverything(String fileName, String mimeType, byte[] bytes) {
        try { return new Extracted(ocrPages(fileName, mimeType, bytes, List.of()), "ocr"); }
        catch (ToolMissing e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.TOOL_MISSING, e.getMessage()); }
    }

    private Extracted anydocFirst(String fileName, String mimeType, String type, byte[] bytes) {
        Path dir = temp("quest-anydoc");
        try {
            Path in = dir.resolve("source." + type); Files.write(in, bytes);
            Path out = dir.resolve("out.md");
            var command = new ArrayList<>(List.of(anydocBin, in.toString(), "-o", out.toString()));
            if ("csv".equals(type)) { command.add("--format"); command.add("csv"); }   // never `--ocr`: exit 3 tells us which pages need it
            var ran = launch(command, dir, anydocBin, "QUEST_ANYDOC_BIN", timeout);
            return switch (ran.exitCode()) {
                case 0 -> new Extracted(Files.exists(out) ? Files.readString(out, java.nio.charset.StandardCharsets.UTF_8) : "", "anydoc");
                case 3 -> new Extracted(ocrPages(fileName, mimeType, bytes, pagesNeedingOcr(ran.stderr())), "ocr");
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
        var deadline = Instant.now().plus(timeout);
        try {
            for (var p : pages) {
                if (!wanted.isEmpty() && !wanted.contains(p.number())) continue;
                Duration left = Duration.between(Instant.now(), deadline);
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
    private String builtin(String fileName, String mimeType, byte[] bytes) {
        var sb = new StringBuilder();
        for (var p : slides.process(fileName, mimeType, bytes).pages())
            sb.append("## Page ").append(p.number()).append("\n\n").append(p.text().strip()).append("\n\n");
        return sb.toString();
    }

    private ProcessRunner.Ran launch(List<String> command, Path dir, String bin, String envVar, Duration box) {
        try { return runner.run(command, dir, box); }
        catch (ProcessRunner.Timeout e) { throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, ApiError.IO, e.getMessage()); }
        catch (IOException e) { throw new ToolMissing(bin + " could not be started — set " + envVar + " to its path or put it on PATH (" + e.getMessage() + ")"); }
    }

    // ---------------------------------------------------------------- reading what the tools said

    /** anydoc's exit-1 stderr, which names the refusal before the colon; anything unrecognised reads as malformed. */
    public static String codeOf(String stderr) {
        String s = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (s.contains("encrypted")) return ApiError.ENCRYPTED;
        if (s.contains("unsupported input")) return ApiError.UNSUPPORTED;
        if (s.contains("io error")) return ApiError.IO;
        return ApiError.MALFORMED;
    }

    /** `page 1 of 1 needs OCR` → [1]; `all 5 pages need OCR` → [] (every page). */
    public static List<Integer> pagesNeedingOcr(String stderr) {
        String s = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (s.contains("all ")) return List.of();
        Matcher m = PAGES_NEEDING_OCR.matcher(s);
        if (!m.find()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : m.group(1).split("[,\\s]+")) if (!part.isBlank()) out.add(Integer.parseInt(part));
        return out;
    }

    // ---------------------------------------------------------------- the temporary directory

    static Path temp(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch (IOException e) { throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.IO, e.getMessage()); }
    }

    /** Depth-first, so the directory itself goes last; a file that will not delete is a temporary file and is logged by nobody. */
    static void wipe(Path dir) {
        try (var s = Files.walk(dir)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (IOException ignored) { /* a temp directory */ }
    }
}
