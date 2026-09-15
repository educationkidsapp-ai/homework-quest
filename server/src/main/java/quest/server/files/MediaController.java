package quest.server.files;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final FileStore files; private final PageImageRepository pageImages; private final ChildMediaRepository childMedia; private final MediaAccess access;

    public MediaController(FileStore files, PageImageRepository pageImages, ChildMediaRepository childMedia, MediaAccess access) {
        this.files = files; this.pageImages = pageImages; this.childMedia = childMedia; this.access = access;
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
