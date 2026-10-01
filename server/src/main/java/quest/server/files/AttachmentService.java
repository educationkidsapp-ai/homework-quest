package quest.server.files;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.platform.SafeText;
import quest.server.tenancy.TenantContext;

/**
 * MH1: `POST /media/attachments` — the upload route RM2 said it had no way to write ("attachments are a reference, not
 * an upload"). The owner's item 6 is a weekly plan that <em>is</em> an image, so the bytes have to arrive somewhere.
 *
 * <p><strong>Images only, and small ones.</strong> A plan is a photograph or a screenshot of a timetable: jpeg, png or
 * webp, at most {@value #MAX_BYTES} bytes. The type is taken from the bytes' own magic number rather than from the
 * `Content-Type` the client sent — a header is whatever the uploader typed, and a `.pdf` announced as `image/png`
 * would be served back to every parent of the department as one.
 *
 * <p>S1 (owner's list of 2026-10-01): a weekly plan may also be a <strong>PDF</strong>, at most {@value #MAX_PDF_BYTES}
 * bytes and recognised the same way, by its `%PDF-` signature. `BroadcastService` keeps it to weekly plans.
 */
@Service
public class AttachmentService {
    /** 5 MB: a page photographed on a phone, not a scan of the year. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    /** 10 MB: a week's plan exported from a document. */
    public static final long MAX_PDF_BYTES = 10L * 1024 * 1024;
    public static final String PDF = "application/pdf";
    /** The three the app and the dashboard both render inline, and the one they link to. */
    private static final List<String> TYPES = List.of("image/jpeg", "image/png", "image/webp", PDF);

    private final FileStore files; private final AttachmentRepository rows; private final TenantContext tenant;
    private final Clock clock;

    public AttachmentService(FileStore files, AttachmentRepository rows, TenantContext tenant, Clock clock) {
        this.files = files; this.rows = rows; this.tenant = tenant; this.clock = clock;
    }

    /** The stored row; the caller then names its id on the broadcast she is composing. */
    @Transactional
    public Entities.AttachmentEntity upload(Principals.User caller, MultipartFile file) {
        String schoolId = tenant.writeSchoolId();
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Send the image or the PDF as `file`.");
        if (file.getSize() > MAX_PDF_BYTES)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "A PDF must be under 10 MB and an image under 5 MB.");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (java.io.IOException e) { throw ApiException.badRequest("That file could not be read."); }
        String mime = sniff(bytes);
        if (!PDF.equals(mime) && bytes.length > MAX_BYTES)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "An image must be under 5 MB.");
        String id = UUID.randomUUID().toString();
        var stored = files.put("attachments/" + schoolId + "/" + id, bytes, mime);
        var row = new Entities.AttachmentEntity();
        row.setId(id); row.setSchoolId(schoolId); row.setUploadedBy(caller.userId());
        row.setName(name(file.getOriginalFilename(), mime)); row.setMimeType(mime);
        row.setSizeBytes(stored.size()); row.setStoragePath(stored.path()); row.setCreatedAt(clock.instant());
        return rows.save(row);
    }

    /**
     * What the bytes actually are. Three image signatures — JPEG starts `FF D8 FF`, PNG `89 P N G`, and WebP is a
     * RIFF container whose fourth word is `WEBP` — and a PDF's `%PDF-`.
     */
    private static String sniff(byte[] b) {
        String image = imageType(b);
        if (image != null) return image;
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return PDF;
        throw ApiException.badRequest("An attachment is a JPEG, PNG or WebP image, or a PDF.");
    }

    /** The image type the bytes are, or null; shared with the school logo upload, which takes images only. */
    public static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 7 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 11 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    /** `image.png`, `file.pdf`: the name a file with none of its own is given, and the download falls back to. */
    public static String defaultName(String mime) { return PDF.equals(mime) ? "file.pdf" : "image." + mime.substring("image/".length()); }

    /** The file name as a label, safe to print: the caller's own if it has one, else the type's own extension. */
    private static String name(String original, String mime) {
        String cleaned = original == null ? "" : original.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).trim();
        String safe = SafeText.plainText(cleaned, "name", 200);
        return safe == null || safe.isBlank() ? defaultName(mime) : safe.toLowerCase(Locale.ROOT);
    }

    /** The only allowed media types, for the runbook and the refusal message above. */
    public static List<String> types() { return TYPES; }
}
