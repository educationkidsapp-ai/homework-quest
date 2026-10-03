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
        static Attachment of(Entities.AttachmentEntity row) {
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
    private final AttachmentRepository attachments; private final AttachmentService uploads; private final quest.server.chat.ChatAttachments chatFiles;

    public MediaController(FileStore files, PageImageRepository pageImages, ChildMediaRepository childMedia, MediaAccess access,
                           AttachmentRepository attachments, AttachmentService uploads, quest.server.chat.ChatAttachments chatFiles) {
        this.files = files; this.pageImages = pageImages; this.childMedia = childMedia; this.access = access;
        this.attachments = attachments; this.uploads = uploads; this.chatFiles = chatFiles;
    }

    /**
     * MH1 (owner's items 6 and 7): the image a manager, a coordinator or a teacher attaches to a broadcast. It is
     * uploaded on its own, before the broadcast exists, so the composer can show it and then post — `attachmentId` on
     * `POST /management/broadcasts` is what ties the two together, and an upload nobody attaches is readable by its
     * uploader alone.
     */
    /**
     * B5: `purpose=chat` uploads a file for a chat message instead ({@link quest.server.chat.ChatAttachments}) — any
     * dashboard role, and the one upload a <strong>parent</strong> may make, naming the child the conversation is about
     * as `childId`. Absent (or `broadcast`) is MH1's upload, unchanged; a parent asking for that is 400.
     */
    @PreAuthorize("@permit.has('media.attachment.write')")
    @PostMapping(value = "/media/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadAttachment(@AuthenticationPrincipal Principals.User caller, @AuthenticationPrincipal Principals.Parent parent,
                                       @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
                                       @RequestParam(value = "purpose", required = false) String purpose,
                                       @RequestParam(value = "childId", required = false) String childId) {
        boolean chat = Entities.CHAT.equals(purpose);
        if (purpose != null && !chat && !Entities.BROADCAST.equals(purpose)) throw ApiException.badRequest("purpose is `chat` or `broadcast`.");
        if (parent != null) {
            if (!chat) throw ApiException.badRequest("Send purpose=chat and childId: a parent uploads files for a conversation.");
            return Attachment.of(chatFiles.uploadForParent(parent, childId, file));
        }
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return Attachment.of(chat ? chatFiles.uploadForStaff(caller, file) : uploads.upload(caller, file));
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
                                             @AuthenticationPrincipal Principals.User user,
                                             @RequestParam(value = "w", required = false) Integer w) {
        var row = attachments.findOneById(id).orElseThrow(() -> ApiException.notFound("media"));
        access.requireAttachment(row, parent, user);
        var blob = files.get(row.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        byte[] bytes = blob.bytes(); String type = blob.mimeType();
        // B5: `?w=` — a chat bubble's copy, a JPEG at most that wide and turned upright. A PDF, a WebP, or an image
        // already that narrow answers the original, so the parameter is always safe to send.
        if (w != null) {
            var small = ImageInfo.downscale(bytes, type, Math.clamp(w, ImageInfo.MIN_THUMB, ImageInfo.MAX_THUMB));
            if (small != null) { bytes = small; type = MediaType.IMAGE_JPEG_VALUE; }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate())
                // `nosniff` and an explicit `inline` disposition: only the sniffed types — three images and, S1, a
                // PDF — can ever be the content type, so a browser shows the plan rather than guessing at it.
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=\"" + downloadName(row) + "\"")
                .contentType(MediaType.parseMediaType(type)).body(bytes);
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
