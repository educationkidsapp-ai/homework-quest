package quest.server.materials;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.AttachmentRef;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 31: the teacher's own "My teaching material / دفتر التحضير" page
 * (`quest.api.dashboard.TeachingMaterials`).
 *
 * <p>`materials.self` is TEACHER and nobody else: the rows are her assignments', the upload is hers, and a parent or
 * a child never reaches any of it. Behind `teachingMaterials`, so a school that does not track material never sees
 * the routes (404).
 */
@RestController
@FeatureFlag(FlagKeys.TEACHING_MATERIALS)
@Tag(name = "Teaching material", description = "A teacher's weekly teaching material, per class and subject")
public class TeacherMaterialsController {
    private final TeachingMaterialService materials; private final Json json;

    public TeacherMaterialsController(TeachingMaterialService materials, Json json) {
        this.materials = materials; this.json = json;
    }

    @GetMapping(value = "/teacher/materials", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('materials.self')")
    public MaterialDto.TeacherMaterials myMaterials(@AuthenticationPrincipal Principals.User caller,
                                                    @RequestParam(required = false) String week) {
        return materials.mine(caller, week);
    }

    @PostMapping(value = "/teacher/materials/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('materials.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttachmentRef.class)))
    public String uploadMaterial(@AuthenticationPrincipal Principals.User caller, @RequestPart("file") MultipartFile file) {
        return json.encodeShared(materials.upload(caller, file), AttachmentRef.Companion.serializer());
    }

    @PutMapping(value = "/teacher/materials/{classId}/{subject}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('materials.self')")
    public MaterialDto.MaterialRow submitMaterial(@AuthenticationPrincipal Principals.User caller,
                                                  @PathVariable String classId, @PathVariable String subject,
                                                  @RequestParam(required = false) String week,
                                                  @RequestBody MaterialDto.SubmitMaterialRequest body) {
        return materials.submit(caller, classId, subject, week, body);
    }
}
