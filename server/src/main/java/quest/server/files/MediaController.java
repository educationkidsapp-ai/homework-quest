package quest.server.files;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.children.ChildMediaRepository;
import quest.server.config.ApiException;
import quest.server.content.PageImageRepository;

/**
 * Id-addressed media: rendered page images (`/media/pages/{id}`) referenced from a lesson, and children's recordings
 * and drawings (`/media/child/{id}`) shown in the parent panel.
 *
 * <p><strong>Authenticated and scoped by school</strong> (P1.9, closing the gap P1.8 opened the door on). The ids are
 * not unguessable — `StopIds.pageImageId` builds them as `<first 8 of the lesson id>:page-N` — so until now whoever
 * learnt one lesson id could read every page crop of that lesson without a token, across schools. Both routes now sit
 * behind `SecurityConfig`'s `authenticated()` (anonymous → 401) and every request is resolved back to its lesson or
 * child by {@link MediaAccess}: a parent reads a published lesson of one of her children's schools and her own
 * child's recordings, a dashboard user reads what her tenant scope contains, and anything else is 404 rather than 403
 * so no id is ever confirmed. Both clients send a token already — the app attaches the parent's Firebase ID token
 * (`shared/src/commonMain/kotlin/quest/feature/journey/data/LessonImages.kt`) and `webAdmin`'s preview its dashboard
 * JWT (`RemoteAdminApi.imageBytes`).
 *
 * <p>The responses stay cacheable but are `private`: they are answers to an authorised request and must never be
 * served from a shared cache to the next caller.
 */
@RestController
@Tag(name = "Media", description = "Page crops and child recordings")
public class MediaController {
    /**
     * MH1: what `POST /media/attachments` answers — the reference a composer then names as `attachmentId`. `sizeBytes`
     * and `type` are what was actually stored, not what was sent: the type is sniffed from the bytes.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AttachmentRef")
    public record Attachment(String id, String name, String type, long sizeBytes, Integer width, Integer height) {
        public static Attachment of(Entities.AttachmentEntity row) {
            return new Attachment(row.getId(), row.getName(), row.getMimeType(), row.getSizeBytes(), row.getWidth(), row.getHeight());
        }
    }

    /**
     * A file name safe to put inside a quoted header: the stored name is already plain text and lower-cased, but a
     * quote, a semicolon or a newline in it would end the header early, so only `[a-z0-9._-]` survives and anything
     * else falls back to the media type's own extension.
     */
    private static String downloadName(Entities.AttachmentEntity row) {
        var out = new StringBuilder();
        for (char c : row.getName().toCharArray())
            if (c == '.' || c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
        String safe = out.toString();
        return safe.length() < 3 || safe.startsWith(".") ? AttachmentService.defaultName(row.getMimeType()) : safe;
    }

    private final FileStore files; private final PageImageRepository pageImages; private final ChildMediaRepository childMedia; private final MediaAccess access;
    private final AttachmentRepository attachments; private final AttachmentService uploads;

    public MediaController(FileStore files, PageImageRepository pageImages, ChildMediaRepository childMedia, MediaAccess access,
                           AttachmentRepository attachments, AttachmentService uploads) {
        this.files = files; this.pageImages = pageImages; this.childMedia = childMedia; this.access = access;
        this.attachments = attachments; this.uploads = uploads;
    }

    /**
     * MH1 (owner's items 6 and 7): the image a manager, a coordinator or a teacher attaches to a broadcast. It is
     * uploaded on its own, before the broadcast exists, so the composer can show it and then post — `attachmentId` on
     * `POST /management/broadcasts` is what ties the two together, and an upload nobody attaches is readable by its
     * uploader alone.
     */
    @PreAuthorize("@permit.has('media.attachment.write')")
    @PostMapping(value = "/media/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadAttachment(@AuthenticationPrincipal Principals.User caller,
                                       @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return Attachment.of(uploads.upload(caller, file));
    }

    /**
     * The bytes, to whoever may read a broadcast that carries them — or to the uploader before she has attached it
     * anywhere ({@link MediaAccess#requireAttachment}). Cached like a page crop and `private` for the same reason: it
     * is the answer to an authorised request and must never be served from a shared cache to the next caller.
     *
     * <p>B5: `?w=` is hidden from the OpenAPI document on purpose — a new parameter there would move the generated
     * clients' positional `observe` argument, which every existing caller passes. It is documented in the runbook.
     */
    @PreAuthorize("@permit.has('media.attachment.read')")
    @GetMapping("/media/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable String id,
                                             @AuthenticationPrincipal Principals.Parent parent,
                                             @AuthenticationPrincipal Principals.User user,
                                             @io.swagger.v3.oas.annotations.Parameter(hidden = true)
                                             @RequestParam(value = "w", required = false) Integer w) {
        var row = attachments.findOneById(id).orElseThrow(() -> ApiException.notFound("media"));
        access.requireAttachment(row, parent, user);
        var blob = w == null ? null : copy(row, w);
        if (blob == null) blob = files.get(row.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        // An id names one file for ever, so a reader may keep it as long as she likes — in her own cache only.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                // `nosniff` and an explicit `inline` disposition: only the sniffed types — three images and, S1, a
                // PDF — can ever be the content type, so a browser shows the plan rather than guessing at it.
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=\"" + downloadName(row) + "\"")
                .contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }

    /**
     * B5: `?w=` — a chat bubble's copy, a JPEG turned upright, at the fixed width {@code asked} rounds up to
     * ({@link ImageInfo#WIDTHS}). Made once and stored beside the original, so a width is decoded once per image
     * however often it is asked for; null — answer the original — for a PDF, a WebP, or an image already that narrow.
     */
    private FileStore.Blob copy(Entities.AttachmentEntity row, int asked) {
        if (!row.getMimeType().startsWith("image/") || "image/webp".equals(row.getMimeType())) return null;
        int width = ImageInfo.width(asked);
        if (row.getWidth() != null && row.getWidth() <= width) return null;
        String path = ImageInfo.copyPath(row.getStoragePath(), width);
        var kept = files.get(path);
        if (kept.isPresent()) return kept.get();
        var original = files.get(row.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        byte[] small = ImageInfo.downscale(original.bytes(), original.mimeType(), width);
        if (small == null) return null;
        files.put(path, small, MediaType.IMAGE_JPEG_VALUE);
        return new FileStore.Blob(small, MediaType.IMAGE_JPEG_VALUE);
    }

    @PreAuthorize("@permit.has('media.page.read')")
    @GetMapping("/media/pages/{id}")
    public ResponseEntity<byte[]> pageImage(@PathVariable String id,
                                            @AuthenticationPrincipal Principals.Parent parent,
                                            @AuthenticationPrincipal Principals.User user) {
        var img = pageImages.findById(id).orElseThrow(() -> ApiException.notFound("media"));
        access.requirePage(img.getLessonId(), parent, user);
        var blob = files.get(img.getStoragePath()).orElseThrow(() -> ApiException.notFound("page image file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate()).contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }

    @PreAuthorize("@permit.has('media.child.read')")
    @GetMapping("/media/child/{id}")
    public ResponseEntity<byte[]> childMedia(@PathVariable String id,
                                             @AuthenticationPrincipal Principals.Parent parent,
                                             @AuthenticationPrincipal Principals.User user) {
        var m = childMedia.findById(id).orElseThrow(() -> ApiException.notFound("media"));
        access.requireChild(m.getChildId(), parent, user);
        var blob = files.get(m.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate()).contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }
}
