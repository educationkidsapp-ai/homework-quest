package quest.server.files;

import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import quest.server.children.ChildMediaRepository;
import quest.server.config.ApiException;
import quest.server.content.PageImageRepository;

/**
 * Public, unguessable-id media: rendered page images (`/media/pages/{id}`) referenced from published lessons,
 * and children's recordings/drawings (`/media/child/{id}`) shown in the parent panel.
 */
@RestController
public class MediaController {
    private final FileStore files; private final PageImageRepository pageImages; private final ChildMediaRepository childMedia;
    public MediaController(FileStore files, PageImageRepository pageImages, ChildMediaRepository childMedia) { this.files = files; this.pageImages = pageImages; this.childMedia = childMedia; }

    @GetMapping("/media/pages/{id}")
    public ResponseEntity<byte[]> page(@PathVariable String id) {
        var img = pageImages.findById(id).orElseThrow(() -> ApiException.notFound("page image"));
        var blob = files.get(img.getStoragePath()).orElseThrow(() -> ApiException.notFound("page image file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic()).contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }

    @GetMapping("/media/child/{id}")
    public ResponseEntity<byte[]> child(@PathVariable String id) {
        var m = childMedia.findById(id).orElseThrow(() -> ApiException.notFound("media"));
        var blob = files.get(m.getStoragePath()).orElseThrow(() -> ApiException.notFound("media file"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS)).contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
    }
}
