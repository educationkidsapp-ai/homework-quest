package quest.server.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.api.AdminLesson;
import quest.api.ConfirmedSkill;
import quest.api.CreateLessonRequest;
import quest.api.JobRef;
import quest.api.LessonFilter;
import quest.api.dto.Curriculum;
import quest.api.dto.LessonStatus;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.api.dto.Subject;
import quest.server.analysis.AnalysisService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;

/** `/admin/lessons/**`, `/admin/stops/**`, `/admin/plays/**` — the admin panel's `AdminApi`. */
@RestController
@Tag(name = "Admin lessons", description = "Lesson pipeline, plays and stops")
public class AdminLessonController {
    private final AdminLessonService service; private final Json json;
    public AdminLessonController(AdminLessonService service, Json json) { this.service = service; this.json = json; }

    @GetMapping(value = "/admin/lessons", produces = MediaType.APPLICATION_JSON_VALUE)
    public String listLessons(@RequestParam(required = false) String curriculum, @RequestParam(required = false) Integer grade, @RequestParam(required = false) String subject,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        try {
            var filter = new LessonFilter(curriculum == null ? null : Curriculum.valueOf(curriculum.toUpperCase()), grade, subject == null ? null : Subject.valueOf(subject.toUpperCase()),
                    from == null ? null : kotlinx.datetime.LocalDate.Companion.parse(from, kotlinx.datetime.LocalDate.Formats.INSTANCE.getISO()), to == null ? null : kotlinx.datetime.LocalDate.Companion.parse(to, kotlinx.datetime.LocalDate.Formats.INSTANCE.getISO()));
            return json.encodeShared(service.list(filter), BuiltinSerializersKt.ListSerializer(AdminLesson.Companion.serializer()));
        } catch (IllegalArgumentException e) { throw ApiException.badRequest("bad filter: " + e.getMessage()); }
    }

    @PostMapping(value = "/admin/lessons", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public String createLesson(@RequestBody String body, @AuthenticationPrincipal Principals.User admin) {
        return json.encodeShared(service.create(decode(body, CreateLessonRequest.Companion.serializer()), admin), AdminLesson.Companion.serializer());
    }

    @GetMapping(value = "/admin/lessons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getLesson(@PathVariable String id) { return json.encodeShared(service.toAdmin(service.get(id), true), AdminLesson.Companion.serializer()); }

    @PostMapping(value = "/admin/lessons/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String uploadFiles(@PathVariable String id, @RequestPart("files") List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) throw ApiException.badRequest("No files.");
        if (files.size() > 10) throw ApiException.badRequest("At most 10 files per lesson.");
        List<AnalysisService.Upload> uploads = new ArrayList<>();
        for (var f : files) uploads.add(new AnalysisService.Upload(f.getOriginalFilename(), f.getContentType(), f.getBytes()));
        return job(id, service.upload(id, uploads));
    }

    @PostMapping(value = "/admin/lessons/{id}/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public String analyze(@PathVariable String id) { return job(id, service.analyze(id)); }

    @PostMapping(value = "/admin/lessons/{id}/skills", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String confirmSkills(@PathVariable String id, @RequestBody String body) {
        List<ConfirmedSkill> skills = decode(body, BuiltinSerializersKt.ListSerializer(ConfirmedSkill.Companion.serializer()));
        return job(id, service.confirmSkills(id, skills));
    }

    @PutMapping(value = "/admin/stops/{stopId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String updateStop(@PathVariable String stopId, @RequestBody String body) {
        return json.encodeShared(service.updateStop(stopId, decode(body, Stop.Companion.serializer())), Stop.Companion.serializer());
    }

    // ---- manual authoring
    @PostMapping(value = "/admin/lessons/{id}/plays", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String createPlay(@PathVariable String id, @RequestBody String body) {
        var req = decode(body, quest.api.CreatePlayRequest.Companion.serializer());
        return json.encodeShared(service.createPlay(id, req.getLevel(), req.getVariant()), quest.api.AdminPlay.Companion.serializer());
    }

    @PostMapping(value = "/admin/plays/{playId}/stops", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String addStop(@PathVariable String playId, @RequestBody String body) {
        return json.encodeShared(service.addStop(playId, decode(body, Stop.Companion.serializer())), Stop.Companion.serializer());
    }

    @DeleteMapping("/admin/stops/{stopId}")
    public ResponseEntity<Void> deleteStop(@PathVariable String stopId) { service.deleteStop(stopId); return ResponseEntity.noContent().build(); }

    @PutMapping(value = "/admin/plays/{playId}/order", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String reorder(@PathVariable String playId, @RequestBody String body) {
        var req = decode(body, quest.api.ReorderRequest.Companion.serializer());
        return json.encodeShared(service.reorderStops(playId, req.getStopIds()), Play.Companion.serializer());
    }

    @PostMapping(value = "/admin/lessons/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String uploadImage(@PathVariable String id, @RequestPart("file") MultipartFile file) throws IOException {
        return json.encodeShared(service.uploadImage(id, file.getOriginalFilename(), file.getContentType(), file.getBytes()), quest.api.LessonImage.Companion.serializer());
    }

    @PostMapping(value = "/admin/lessons/{id}/generate-from-text", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String generateFromText(@PathVariable String id, @RequestBody String body) {
        var req = decode(body, quest.api.GenerateFromTextRequest.Companion.serializer());
        return job(id, service.generateFromText(id, req.getText()));
    }

    @PostMapping(value = "/admin/stops/{stopId}/regenerate", produces = MediaType.APPLICATION_JSON_VALUE)
    public String regenerateStop(@PathVariable String stopId) { return json.encodeShared(service.regenerateStop(stopId), Stop.Companion.serializer()); }

    @PostMapping(value = "/admin/plays/{playId}/regenerate", produces = MediaType.APPLICATION_JSON_VALUE)
    public String regeneratePlay(@PathVariable String playId) { return json.encodeShared(service.regeneratePlay(playId), Play.Companion.serializer()); }

    @PutMapping(value = "/admin/lessons/{id}/parent-panel", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String updatePanel(@PathVariable String id, @RequestBody String body) {
        return json.encodeShared(service.updatePanel(id, decode(body, ParentPanel.Companion.serializer())), ParentPanel.Companion.serializer());
    }

    @PostMapping(value = "/admin/lessons/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public String publish(@PathVariable String id) { return json.encodeShared(service.publish(id), AdminLesson.Companion.serializer()); }

    @PostMapping(value = "/admin/lessons/{id}/unpublish", produces = MediaType.APPLICATION_JSON_VALUE)
    public String unpublish(@PathVariable String id) { return json.encodeShared(service.unpublish(id), AdminLesson.Companion.serializer()); }

    @DeleteMapping("/admin/lessons/{id}/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFiles(@PathVariable String id) { service.deleteFiles(id); }

    @PostMapping(value = "/admin/lessons/{id}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public String retry(@PathVariable String id) { return job(id, service.retry(id)); }

    @PostMapping(value = "/admin/lessons/{id}/steps/{step}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public String retryStep(@PathVariable String id, @PathVariable String step) { return job(id, service.retryStep(id, quest.server.analysis.LessonSteps.parse(step))); }

    @DeleteMapping(value = "/admin/lessons/failed", produces = MediaType.APPLICATION_JSON_VALUE)
    public String deleteFailed() { return "{\"deleted\":" + service.deleteFailed() + "}"; }

    @DeleteMapping("/admin/lessons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable String id) { service.delete(id); }

    private String job(String id, LessonStatus status) { return json.encodeShared(new JobRef(id, status), JobRef.Companion.serializer()); }
    private <T> T decode(String body, KSerializer<T> serializer) { try { return json.decodeShared(body, serializer); } catch (Exception e) { throw ApiException.badRequest("Malformed request: " + e.getMessage()); } }
}
