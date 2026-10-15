package quest.server.children;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.EveningCard;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 28: the parent's own card about today, on her own child. The child in the path is what the flag is read
 * from ({@link quest.server.flags.FeatureFlagInterceptor}) and what the ownership check is made against — another
 * family's child is a 404, as it is on every other `/children/{id}/…` route.
 */
@RestController
@Tag(name = "Evening card", description = "Feature 28: one card per child about today, for her parent")
@FeatureFlag(FlagKeys.PARENT_EVENING_CARD)
public class EveningCardController {
    private final ChildService children; private final EveningCardService cards; private final Json json;

    public EveningCardController(ChildService children, EveningCardService cards, Json json) {
        this.children = children; this.cards = cards; this.json = json;
    }

    /**
     * Today's card, generated on the first request after 16:00 school time and stored, so every later request answers
     * the same words without asking the model again.
     */
    @PreAuthorize("@permit.has('child.eveningCard.read')")
    @GetMapping(value = "/children/{id}/evening-card", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EveningCard.class)))
    @ApiResponse(responseCode = "204", description = "Nothing to show: before 16:00 school time, a day the school does not teach, or no homework of hers today")
    public ResponseEntity<String> eveningCard(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        var child = children.owned(id, parent);
        var row = cards.today(child);
        if (row == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(json.encodeShared(EveningCardService.view(row, cards.lessonIdsOf(row)), EveningCard.Companion.serializer()));
    }
}
