package quest.server.documents;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.ApiError;
import quest.server.ai.AiFailure;
import quest.server.ai.AiInputGuard;
import quest.server.ai.AiService;
import quest.server.ai.AiTask;
import quest.server.ai.AiVerdict;
import quest.server.analysis.DocumentText;
import quest.server.analysis.Prompts;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.documents.Entities.DocumentSummaryEntity;
import quest.server.files.AttachmentService;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.TenantContext;

/**
 * Feature 26: <strong>a circular arrives and the leader is told what it says.</strong> {@code VoiceDraftService}'s
 * shape with the input the other way round — a file instead of her own words — so the same three rules hold: the
 * assistant prepares, the validator decides whether the answer may be stored, and <strong>nothing is sent</strong>.
 * "Draft announcement" pre-fills the compose sheet; Send is the unchanged `POST /management/broadcasts`.
 *
 * <p><strong>The file is never stored.</strong> The bytes live in this method's own frame and in the temporary
 * directory {@link DocumentText} deletes in a `finally`; nothing is written to the {@link quest.server.files.FileStore}
 * and no row records a path, because there is none. That is the feature's one privacy promise and the reason a
 * circular full of other people's business cannot be downloaded back out of the platform afterwards. What travels to
 * the provider is the extracted text with e-mail addresses and telephone numbers masked
 * ({@link AiInputGuard#numbersOnly}) — a guard, not a promise about prose: a letter may name anybody, and the leader
 * chose to read it.
 *
 * <p><strong>The type is the first bytes, never the name.</strong> `%PDF-`, a ZIP holding `word/`, and the three
 * image signatures {@link AttachmentService#imageType} already sniffs for chat photographs. A `.pdf` that is really
 * a zip bomb and a `.jpg` that is really a Word file are both refused before a process is started.
 *
 * <p>E1's three phases, for {@code VoiceDraftService}'s reason: read what the prompt needs, let the transaction go,
 * extract and call the model, then take one short transaction to store the answer. Extraction is OCR for a photograph
 * — tens of seconds — and ten leaders uploading at once must not be the whole connection pool.
 */
@Service
public class DocumentReaderService {
    /** 10 MB, the spec's limit and `AttachmentService`'s for a PDF — above Spring's own part limit nothing arrives. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    /** Twenty pages. A ministry circular is one or two; a scanned textbook is somebody uploading the wrong thing. */
    public static final int MAX_PAGES = 20;
    /** `{ "code": "rephrase" }` — the assistant could not summarise the document in a shape we may store, twice. */
    public static final String REPHRASE = "rephrase";
    /** What the model is sent. Twenty pages of dense Arabic is well inside it; a pathological scan is cut. */
    static final int MAX_MARKDOWN = 80_000;
    private static final int MAX_TITLE = 120, MAX_SUMMARY = 1200, MAX_BODY = 1000, MAX_LABEL = 200, MAX_ACTION = 300, MAX_LIST = 10;

    private final DocumentSummaryRepository summaries; private final DocumentText text; private final AiService ai;
    private final SchoolCalendar calendar; private final Json json; private final TenantContext tenant;
    private final Clock clock; private final TransactionTemplate tx;

    public DocumentReaderService(DocumentSummaryRepository summaries, DocumentText text, AiService ai,
                                 SchoolCalendar calendar, Json json, TenantContext tenant, Clock clock,
                                 PlatformTransactionManager transactions) {
        this.summaries = summaries; this.text = text; this.ai = ai; this.calendar = calendar; this.json = json;
        this.tenant = tenant; this.clock = clock; this.tx = new TransactionTemplate(transactions);
    }

    // ---------------------------------------------------------------- reading one

    /** One circular in, one stored summary out — and the file itself gone before this method returns. */
    public DocumentDto.View read(Principals.User caller, MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Send the document as `file`.");
        if (file.getSize() > MAX_BYTES)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiError.TOO_LARGE, "A document must be under 10 MB.");
        String schoolId = tenant.writeSchoolId();
        // The signature is read before the part is pulled into the heap, as `AttachmentService.store` does.
        String type;
        try (var in = file.getInputStream()) { type = sniff(in.readNBytes(16)); }
        catch (IOException e) { throw ApiException.badRequest("That file could not be read."); }
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw ApiException.badRequest("That file could not be read."); }
        if ("docx".equals(type) && !isWordDocument(bytes))
            throw ApiException.badRequest("A document is a PDF, a Word file, or a JPEG, PNG or WebP photograph.");
        String fileName = name(file.getOriginalFilename(), type);
        // Counted before anything is extracted, so a 300-page scan is refused without rendering a single page.
        int pages = pagesOf(type, bytes);
        tooLongIf(pages);

        var extracted = text.extract(fileName, mimeOf(type), type, bytes);
        String markdown = extracted.markdown() == null ? "" : extracted.markdown().strip();
        if (markdown.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.UNREADABLE_FILE,
                "No text came out of " + fileName + ". Photograph the page straight on, in better light, or upload the PDF.");
        // A Word file's length is only knowable once it has been read: anydoc heads each page `## Page N`, as OCR does.
        if (pages == 0) pages = Math.max(1, countPages(markdown));
        tooLongIf(pages);
        if (markdown.length() > MAX_MARKDOWN) markdown = markdown.substring(0, MAX_MARKDOWN);

        var answer = summarise(schoolId, fileName, markdown);
        var row = new DocumentSummaryEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setAuthorId(caller.userId());
        row.setFileName(fileName); row.setPages(pages);
        row.setSummaryJson(json.write(stored(answer)));
        row.setCreatedAt(clock.instant());
        tx.executeWithoutResult(t -> summaries.save(row));
        return view(row);
    }

    /** Her last {@code limit} summaries, newest first. */
    public List<DocumentDto.View> mine(Principals.User caller, int limit) {
        return summaries.byAuthor(tenant.writeSchoolId(), caller.userId(), org.springframework.data.domain.PageRequest.of(0, limit))
                .stream().map(this::view).toList();
    }

    /** One of hers, or 404 — another author's and another school's are the same answer. */
    public DocumentDto.View one(Principals.User caller, String id) {
        return view(summaries.findOneOfAuthor(tenant.writeSchoolId(), id, caller.userId())
                .orElseThrow(() -> ApiException.notFound("document summary")));
    }

    // ---------------------------------------------------------------- the model and its answer

    /** The validated answer, or the caller's error: 422 `rephrase` after two tries, 502 `model_failed` otherwise. */
    private JsonNode summarise(String schoolId, String fileName, String markdown) {
        String today = LocalDate.now(clock.withZone(calendar.of(schoolId).zone())).toString();
        try {
            return ai.run(AiTask.of("documentReader", "F-" + schoolId, schoolId, Prompts.DOCUMENT_READER,
                            Prompts.userDocumentReader(today, fileName, markdown))
                    .guardedBy(AiInputGuard.numbersOnly())
                    .reading((raw, attempt) -> {
                        JsonNode candidate;
                        try { candidate = json.tree(raw); }
                        catch (RuntimeException e) { return AiVerdict.rejected("the answer was not JSON at all"); }
                        var wrong = problems(candidate);
                        return wrong.isEmpty() ? AiVerdict.ok(candidate) : AiVerdict.rejected(wrong);
                    })
                    .keepingRejects()).json();
        } catch (AiFailure f) {
            if (f.kind() == AiFailure.Kind.INVALID)
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, REPHRASE,
                        "Couldn't read that document, please try another copy of it. " + String.join("; ", f.errors()));
            if (f.isTransient()) throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.MODEL_FAILED,
                    "The AI is busy — try that again in a moment.");
            throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.MODEL_FAILED, f.getMessage());
        }
    }

    /** Everything wrong with one answer, in the words the second attempt is given. Empty means it may be stored. */
    private static List<String> problems(JsonNode answer) {
        var wrong = new ArrayList<String>();
        if (!answer.isObject()) { wrong.add("answer with one JSON object"); return wrong; }
        required(wrong, answer, "title", MAX_TITLE);
        required(wrong, answer, "summaryAr", MAX_SUMMARY);
        required(wrong, answer, "summaryEn", MAX_SUMMARY);
        JsonNode dates = answer.path("keyDates");
        if (isPresent(dates)) {
            if (!dates.isArray()) wrong.add("\"keyDates\" must be a list of objects, or be left out");
            else {
                if (dates.size() > MAX_LIST) wrong.add("\"keyDates\" must hold at most " + MAX_LIST + " dates");
                for (JsonNode date : dates) {
                    if (!date.isObject()) { wrong.add("every entry of \"keyDates\" must be an object with \"date\", \"labelAr\" and \"labelEn\""); continue; }
                    JsonNode day = date.path("date");
                    if (!day.isTextual() || !parses(day.asText()))
                        wrong.add("\"date\" must be a real calendar date written yyyy-MM-dd, and \"" + day.asText("") + "\" is not one");
                    required(wrong, date, "labelAr", MAX_LABEL);
                    required(wrong, date, "labelEn", MAX_LABEL);
                }
            }
        }
        lines(wrong, answer, "actionsAr");
        lines(wrong, answer, "actionsEn");
        JsonNode draft = answer.path("announcement");
        if (isPresent(draft)) {
            if (!draft.isObject()) wrong.add("\"announcement\" must be an object with \"title\", \"bodyAr\" and \"bodyEn\", or be left out");
            else { required(wrong, draft, "title", MAX_TITLE); required(wrong, draft, "bodyAr", MAX_BODY); required(wrong, draft, "bodyEn", MAX_BODY); }
        }
        return List.copyOf(wrong);
    }

    private static void required(List<String> wrong, JsonNode answer, String field, int max) {
        JsonNode value = answer.path(field);
        if (!value.isTextual() || value.asText().isBlank()) { wrong.add("\"" + field + "\" is required and must be text"); return; }
        if (value.asText().strip().length() > max) wrong.add("\"" + field + "\" must be at most " + max + " characters");
    }

    private static void lines(List<String> wrong, JsonNode answer, String field) {
        JsonNode list = answer.path(field);
        if (!isPresent(list)) return;
        if (!list.isArray()) { wrong.add("\"" + field + "\" must be a list of short lines, or be left out"); return; }
        if (list.size() > MAX_LIST) wrong.add("\"" + field + "\" must hold at most " + MAX_LIST + " lines");
        for (JsonNode line : list)
            if (!line.isTextual() || line.asText().isBlank() || line.asText().strip().length() > MAX_ACTION) {
                wrong.add("every line of \"" + field + "\" must be text of at most " + MAX_ACTION + " characters");
                break;
            }
    }

    private static boolean isPresent(JsonNode node) { return !node.isMissingNode() && !node.isNull(); }

    /** A real calendar date, so the 29th of February 2027 is rejected rather than rounded. */
    private static boolean parses(String day) {
        try { LocalDate.parse(day.strip()); return true; } catch (DateTimeParseException e) { return false; }
    }

    // ---------------------------------------------------------------- what is stored, and what is returned

    /** The accepted answer, normalised to the contract's shape before it becomes one `summary_json` column. */
    private DocumentDto.Body stored(JsonNode answer) {
        var dates = new ArrayList<DocumentDto.KeyDate>();
        for (JsonNode date : answer.path("keyDates"))
            dates.add(new DocumentDto.KeyDate(date.get("date").asText().strip(), cut(date, "labelAr", MAX_LABEL), cut(date, "labelEn", MAX_LABEL)));
        JsonNode draft = answer.path("announcement");
        var announcement = draft.isObject()
                ? new DocumentDto.AnnouncementDraft(cut(draft, "title", MAX_TITLE), cut(draft, "bodyAr", MAX_BODY), cut(draft, "bodyEn", MAX_BODY))
                : null;
        return new DocumentDto.Body(cut(answer, "title", MAX_TITLE),
                cut(answer, "summaryAr", MAX_SUMMARY), cut(answer, "summaryEn", MAX_SUMMARY),
                List.copyOf(dates), strings(answer, "actionsAr"), strings(answer, "actionsEn"), announcement);
    }

    /** The row as the dashboard reads it: the stored answer with the row's own four columns around it. */
    private DocumentDto.View view(DocumentSummaryEntity row) {
        var body = json.read(row.getSummaryJson(), DocumentDto.Body.class);
        return new DocumentDto.View(row.getId(), row.getFileName(), row.getPages(), body.title(),
                body.summaryAr(), body.summaryEn(),
                body.keyDates() == null ? List.of() : body.keyDates(),
                body.actionsAr() == null ? List.of() : body.actionsAr(),
                body.actionsEn() == null ? List.of() : body.actionsEn(),
                body.announcement(), row.getCreatedAt().toEpochMilli());
    }

    private static List<String> strings(JsonNode answer, String field) {
        var out = new ArrayList<String>();
        for (JsonNode line : answer.path(field)) out.add(line.asText().strip());
        return List.copyOf(out);
    }

    private static String cut(JsonNode owner, String field, int max) {
        String value = owner.path(field).asText("").strip();
        return value.length() <= max ? value : value.substring(0, max).strip();
    }

    // ---------------------------------------------------------------- what the bytes are, and how long

    private static void tooLongIf(int pages) {
        if (pages > MAX_PAGES) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.TOO_LONG,
                "The document reader takes at most " + MAX_PAGES + " pages, and this one has " + pages + ".");
    }

    /**
     * The type the bytes actually are: `pdf`, `docx`, or one of the three image types. The extension
     * {@link DocumentText} is given, so the name on the part decides nothing at all.
     */
    static String sniff(byte[] b) {
        String image = AttachmentService.imageType(b);
        if (image != null) return image.substring("image/".length()).replace("jpeg", "jpg");
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "pdf";
        // A ZIP: a .docx, but also a .pptx, a .xlsx and any other archive — which of them is settled by its entries.
        if (b.length > 3 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) return "docx";
        throw ApiException.badRequest("A document is a PDF, a Word file, or a JPEG, PNG or WebP photograph.");
    }

    /** A real Word file carries `word/document.xml`; a presentation and a spreadsheet are the same ZIP and are not. */
    private static boolean isWordDocument(byte[] bytes) {
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().startsWith("word/")) return true;
        } catch (IOException e) { return false; }
        return false;
    }

    private static String mimeOf(String type) {
        return switch (type) { case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "image/" + ("jpg".equals(type) ? "jpeg" : type); };
    }

    /** The page count the limit is checked against: PDFBox's for a PDF, one for a photograph, unknown (0) for Word. */
    private static int pagesOf(String type, byte[] bytes) {
        if (!"pdf".equals(type)) return "docx".equals(type) ? 0 : 1;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.ENCRYPTED,
                    "That PDF is password-protected. Save it without the password and upload it again.");
            int pages = doc.getNumberOfPages();
            if (pages == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.UNREADABLE_FILE, "That PDF has no pages.");
            return pages;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.MALFORMED, "That PDF could not be opened."); }
    }

    /** `## Page N` headings, which is how both anydoc and our OCR head each page. */
    private static int countPages(String markdown) {
        int n = 0;
        for (String line : markdown.split("\n", -1)) if (line.startsWith("## Page ")) n++;
        return n;
    }

    /** The name as a label, safe to print — the uploader's own if it has one, else the type's own extension. */
    private static String name(String original, String type) {
        String cleaned = original == null ? "" : original.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        cleaned = slash < 0 ? cleaned : cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "").strip();
        if (cleaned.isEmpty()) return "document." + type;
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }
}
