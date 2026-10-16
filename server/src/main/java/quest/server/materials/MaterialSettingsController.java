package quest.server.materials;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 31: the "Teaching material deadline" card on the Admin's Attendance settings page.
 *
 * <p>It carries feature 14's own two permissions — `attendance.settings.read` is ADMIN and MANAGERIAL,
 * `attendance.settings.write` ADMIN only — because it is one more rule the school's staff are held to, on the same
 * page and for the same reason: a manager sees the deadline her people are held to, and only the Admin moves it.
 * Behind `teachingMaterials`.
 */
@RestController
@FeatureFlag(FlagKeys.TEACHING_MATERIALS)
@Tag(name = "Teaching material settings", description = "The weekly teaching material deadline and its reminder")
public class MaterialSettingsController {
    private final MaterialDeadlines deadlines;

    public MaterialSettingsController(MaterialDeadlines deadlines) { this.deadlines = deadlines; }

    @GetMapping(value = "/admin/material-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.read')")
    public MaterialDto.MaterialSettings materialSettings() { return deadlines.read(); }

    @PutMapping(value = "/admin/material-settings", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.write')")
    public MaterialDto.MaterialSettings updateMaterialSettings(@AuthenticationPrincipal Principals.User caller,
                                                               @RequestBody MaterialDto.UpdateMaterialSettingsRequest body) {
        return deadlines.update(TeacherScope.require(caller), body);
    }
}
