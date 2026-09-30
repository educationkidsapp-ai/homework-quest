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
    public record Attachment(String id, String name, String type, long sizeBytes) {}

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
        var row = uploads.upload(caller, file);
        return new Attachment(row.getId(), row.getName(), row.getMimeType(), row.getSizeBytes());
    }

    /**
     * The bytes, to whoever may read a broadcast that carries them — or to the uploader before she has attached it
     * anywhere ({@link MediaAccess#requireAttachment}). Cached like a page crop and `private` for the same reason: it
     * is the answer to an authorised request and must never be served from a shared cache to the next caller.
     */
    @PreAuthorize("@permit.has('media.attachment.read')")
    @GetMapping("/media/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable String id,
                                             @AuthenticationPrincipal Principals.Parent parent,
                                             @AuthenticationPrincipal Principals.User user) {
        var row = attachments.findOneById(id).orElseThrow(() -> ApiException.notFound("media"));
        access.requireAttachment(row, parent, user);
        var blob = files.get(row.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate())
                .contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
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
