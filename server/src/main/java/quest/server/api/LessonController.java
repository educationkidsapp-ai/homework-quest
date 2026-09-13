package quest.server.api;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.server.api.dto.ApiError;
import quest.server.api.dto.Requests;
import quest.server.service.LessonService;

@RestController
@RequestMapping("/lessons")
public class LessonController {
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private final LessonService lessons;

    public LessonController(LessonService lessons) { this.lessons = lessons; }

    /** POST /lessons — multipart: `request` (JSON) + `files` (0..n). Returns the job in `uploading`. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Requests.LessonJob create(@RequestPart("request") @Valid Requests.CreateLessonRequest request,
                                     @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        List<LessonService.UploadPart> parts = new ArrayList<>();
        if (files != null) for (MultipartFile f : files) {
            if (f.getSize() > MAX_FILE_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiError.TOO_LARGE, "Each file must be under 25 MB.");
            String mime = f.getContentType() == null ? "application/octet-stream" : f.getContentType();
            parts.add(new LessonService.UploadPart(f.getOriginalFilename() == null ? "file" : f.getOriginalFilename(), mime, f.getBytes()));
        }
        return lessons.create(request, parts);
    }

    @GetMapping("/{id}")
    public Requests.LessonJob get(@PathVariable String id) { return lessons.get(id); }

    @PostMapping("/{id}/confirm")
    public Requests.LessonJob confirm(@PathVariable String id, @RequestBody @Valid Requests.ConfirmSkillsRequest request) { return lessons.confirm(id, request); }

    @DeleteMapping("/{id}/files")
    public Map<String, Integer> deleteFiles(@PathVariable String id) { return Map.of("deleted", lessons.deleteFiles(id)); }
}
