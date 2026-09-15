package quest.server.files;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import quest.server.children.ChildMediaRepository;
import quest.server.config.ApiException;
import quest.server.content.PageImageRepository;

/**
 * Public, id-addressed media: rendered page images (`/media/pages/{id}`) referenced from published lessons, and
 * children's recordings/drawings (`/media/child/{id}`) shown in the parent panel.
 *
 * <p><strong>Known gap, not closed here.</strong> The ids are not unguessable: `StopIds.pageImageId` builds them as
 * `<first 8 of the lesson id>:page-N`, so whoever learns one lesson id can read every page crop of that lesson
 * without a token, across schools. Authorising these two routes through the lesson's school is the fix, but the app
 * downloads them with a bare HTTP client that attaches no bearer token
 * (`shared/src/commonMain/kotlin/quest/feature/journey/data/LessonImages.kt`, built with no `AuthProvider` in
 * `AppModule`), so requiring authentication today would blank every picture in the app. `webAdmin`'s preview does
 * send its token (`RemoteAdminApi.imageBytes`). Sequence: teach the app's loader to send the Firebase token (or move
 * to signed, expiring media URLs), then put `/media/**` behind authentication and scope it by school.
 */
@RestController
@Tag(name = "Media", description = "Page crops and child recordings")
public class MediaController {
    private final FileStore files; private final PageImageRepository pageImages; private final ChildMediaRepository childMedia;
    public MediaController(FileStore files, PageImageRepository pageImages, ChildMediaRepository childMedia) { this.files = files; this.pageImages = pageImages; this.childMedia = childMedia; }

    @PreAuthorize("permitAll")
    @GetMapping("/media/pages/{id}")
    public ResponseEntity<byte[]> pageImage(@PathVariable String id) {
        var img = pageImages.findById(id).orElseThrow(() -> ApiException.notFound("page image"));
        var blob = files.get(img.getStoragePath()).orElseThrow(() -> ApiException.notFound("page image file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic()).contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }

    @PreAuthorize("permitAll")
    @GetMapping("/media/child/{id}")
    public ResponseEntity<byte[]> childMedia(@PathVariable String id) {
        var m = childMedia.findById(id).orElseThrow(() -> ApiException.notFound("media"));
        var blob = files.get(m.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS)).contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }
}
