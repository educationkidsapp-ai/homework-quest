package quest.server.evaluations;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.ParentEvaluation;
import quest.server.auth.Principals;
import quest.server.children.ChildService;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 33: what the <strong>parent</strong> reads — `GET /children/{id}/evaluations`, the approved evaluations of
 * released rounds, in words.
 *
 * <p>The child in the path is what the flag is read from ({@link quest.server.flags.FeatureFlagInterceptor}) and
 * what the ownership check is made against: another family's child is a 404, as on every other `/children/{id}/…`
 * route. `child.evaluations` is PARENT and nobody else.
 *
 * <p><strong>No number, no draft, no state, no reviewer.</strong> {@link ParentEvaluation} has a field for none of
 * them, and {@link ChildEvaluationService#parentEvaluations} maps each rating to a word key before it leaves the
 * server. The child herself never sees this screen: it lives in the app's parent area behind the parent gate.
 */
@RestController
@FeatureFlag(FlagKeys.CHILD_EVALUATIONS)
@Tag(name = "Child evaluations", description = "A teacher's evaluation of each child she teaches, per round")
public class ChildEvaluationsController {
    private final ChildService children; private final ChildEvaluationService evaluations; private final Json json;

    public ChildEvaluationsController(ChildService children, ChildEvaluationService evaluations, Json json) {
        this.children = children; this.evaluations = evaluations; this.json = json;
    }

    @GetMapping(value = "/children/{id}/evaluations", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.evaluations')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ParentEvaluation.class))))
    public String childEvaluations(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        var child = children.owned(id, parent);
        return json.encodeShared(evaluations.parentEvaluations(child),
                BuiltinSerializersKt.ListSerializer(ParentEvaluation.Companion.serializer()));
    }
}
