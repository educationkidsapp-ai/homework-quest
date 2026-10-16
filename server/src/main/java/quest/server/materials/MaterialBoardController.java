package quest.server.materials;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 31: the leaders' "Teaching material / متابعة التحضير" page — the week's board, Mark reviewed, and the CSV.
 *
 * <p>`materials.board` is ADMIN, MANAGERIAL, COORDINATOR, FLOOR_SUPERVISOR and TEACHER: anyone who may be named a
 * direct manager, exactly as `attendance.board` is. What each of them sees is
 * {@link quest.server.staffattendance.StaffBoardScope}'s answer — feature 17's own scope — and not the permission's.
 * Behind `teachingMaterials`.
 */
@RestController
@FeatureFlag(FlagKeys.TEACHING_MATERIALS)
@Tag(name = "Teaching material board", description = "Who handed in their teaching material, and its review")
public class MaterialBoardController {
    private final TeachingMaterialService materials;

    public MaterialBoardController(TeachingMaterialService materials) { this.materials = materials; }

    @GetMapping(value = "/materials/board", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('materials.board')")
    public MaterialDto.MaterialBoard materialBoard(@AuthenticationPrincipal Principals.User caller,
                                                   @RequestParam(required = false) String week) {
        return materials.board(caller, week);
    }

    @GetMapping(value = "/materials/board.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('materials.board')")
    public ResponseEntity<byte[]> materialBoardCsv(@AuthenticationPrincipal Principals.User caller,
                                                   @RequestParam(required = false) String week) {
        var board = materials.board(caller, week);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + MaterialExport.filename(board) + "\"")
                .body(MaterialExport.csv(board));
    }

    @PostMapping(value = "/materials/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('materials.board')")
    public MaterialDto.MaterialRow reviewMaterial(@AuthenticationPrincipal Principals.User caller,
                                                  @PathVariable String id,
                                                  @RequestBody MaterialDto.ReviewMaterialRequest body) {
        return materials.review(caller, id, body);
    }
}
