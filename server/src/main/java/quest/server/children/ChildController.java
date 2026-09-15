package quest.server.children;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.AttemptAck;
import quest.api.dto.AttemptUpload;
import quest.api.dto.Child;
import quest.api.dto.CreateChildRequest;
import quest.api.dto.MapResponse;
import quest.api.dto.MediaKind;
import quest.api.dto.MediaRef;
import quest.api.dto.ProgressResponse;
import quest.api.dto.UpdateChildRequest;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.config.QuestProperties;
import quest.server.files.FileStore;

/** The parent-facing `ContentApi` endpoints (all bodies are the shared-api contract types, encoded with the shared codec). */
@RestController
@Tag(name = "Children", description = "Parent-owned children, map, attempts and progress")
public class ChildController {
    private static final long MAX_MEDIA_BYTES = 10L * 1024 * 1024;
    private final ChildService childService; private final MapService mapService; private final AttemptService attemptService; private final ProgressService progressService;
    private final ChildMediaRepository media; private final StopCompletionRepository stopCompletions; private final FileStore files; private final Json json; private final String publicUrl;

    public ChildController(ChildService childService, MapService mapService, AttemptService attemptService, ProgressService progressService, ChildMediaRepository media, StopCompletionRepository stopCompletions, FileStore files, Json json, QuestProperties props) {
        this.childService = childService; this.mapService = mapService; this.attemptService = attemptService; this.progressService = progressService; this.media = media; this.stopCompletions = stopCompletions; this.files = files; this.json = json;
        this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl();
    }

    @PreAuthorize("@permit.has('child.read')")
    @GetMapping(value = "/children", produces = MediaType.APPLICATION_JSON_VALUE)
    public String listChildren(@AuthenticationPrincipal Principals.Parent parent) {
        return json.encodeShared(childService.list(parent), BuiltinSerializersKt.ListSerializer(Child.Companion.serializer()));
    }

    @PreAuthorize("@permit.has('child.write')")
    @PostMapping(value = "/children", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public String createChild(@AuthenticationPrincipal Principals.Parent parent, @RequestBody String body) {
        var req = decode(body, CreateChildRequest.Companion.serializer());
        return json.encodeShared(childService.create(parent, req), Child.Companion.serializer());
    }

    @PreAuthorize("@permit.has('child.write')")
    @PatchMapping(value = "/children/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String updateChild(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @RequestBody String body) {
        var req = decode(body, UpdateChildRequest.Companion.serializer());
        return json.encodeShared(childService.update(parent, id, req), Child.Companion.serializer());
    }

    @PreAuthorize("@permit.has('child.write')")
    @DeleteMapping("/children/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChild(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) { childService.delete(parent, id); }

    @PreAuthorize("@permit.has('child.read')")
    @GetMapping(value = "/children/{id}/map", produces = MediaType.APPLICATION_JSON_VALUE)
    public String childMap(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @RequestParam String from, @RequestParam String to, @RequestParam(required = false) String today) {
        var child = childService.owned(id, parent);
        LocalDate f, t, d;
        try { f = LocalDate.parse(from); t = LocalDate.parse(to); d = today == null ? LocalDate.now() : LocalDate.parse(today); } catch (Exception e) { throw ApiException.badRequest("from/to must be ISO dates"); }
        if (t.isBefore(f) || f.plusDays(120).isBefore(t)) throw ApiException.badRequest("range must be 0–120 days");
        return json.encodeShared(mapService.map(child, f, t, d), MapResponse.Companion.serializer());
    }

    @PreAuthorize("@permit.has('child.play')")
    @PostMapping(value = "/children/{id}/attempts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String uploadAttempts(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @RequestBody String body) {
        var child = childService.owned(id, parent);
        List<AttemptUpload> uploads = decode(body, BuiltinSerializersKt.ListSerializer(AttemptUpload.Companion.serializer()));
        if (uploads.size() > 500) throw ApiException.badRequest("at most 500 attempts per upload");
        return json.encodeShared(new AttemptAck(attemptService.record(child, uploads)), AttemptAck.Companion.serializer());
    }

    @PreAuthorize("@permit.has('child.play')")
    @PostMapping(value = "/children/{id}/stops/{stopId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String uploadChildMedia(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String stopId, @RequestParam("kind") String kind, @RequestPart("file") MultipartFile file) throws java.io.IOException {
        var child = childService.owned(id, parent);
        MediaKind mk;
        try { mk = MediaKind.valueOf(kind.trim().toUpperCase()); } catch (IllegalArgumentException e) { throw ApiException.badRequest("kind must be recording or drawing"); }
        if (file.getSize() > MAX_MEDIA_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "media must be under 10 MB");
        var mediaId = UUID.randomUUID().toString();
        var mime = file.getContentType() == null ? (mk == MediaKind.RECORDING ? "audio/mp4" : "image/png") : file.getContentType();
        var stored = files.put("media/" + child.getId() + "/" + stopId + "/" + mediaId, file.getBytes(), mime);
        var e = new Entities.ChildMediaEntity();
        e.setId(mediaId); e.setChildId(child.getId()); e.setStopId(stopId); e.setKind(mk.name().toLowerCase()); e.setStoragePath(stored.path()); e.setMimeType(mime); e.setSizeBytes(stored.size()); e.setCreatedAt(Instant.now());
        media.save(e);
        var url = publicUrl + "/media/child/" + mediaId;
        stopCompletions.findById(new Entities.StopCompletionId(child.getId(), stopId)).ifPresent(sc -> { if (mk == MediaKind.RECORDING) sc.setRecordingPath(url); else sc.setDrawingPath(url); stopCompletions.save(sc); });
        return json.encodeShared(new MediaRef(mediaId, url, mk), MediaRef.Companion.serializer());
    }

    @PreAuthorize("@permit.has('child.read')")
    @GetMapping(value = "/children/{id}/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    public String childProgress(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        var child = childService.owned(id, parent);
        return json.encodeShared(progressService.progress(child), ProgressResponse.Companion.serializer());
    }

    private <T> T decode(String body, kotlinx.serialization.KSerializer<T> serializer) {
        try { return json.decodeShared(body, serializer); } catch (Exception e) { throw ApiException.badRequest("Malformed request: " + e.getMessage()); }
    }
}
