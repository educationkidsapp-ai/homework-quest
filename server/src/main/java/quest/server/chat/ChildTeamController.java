package quest.server.chat;

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
import quest.api.dto.TeamMember;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 32: the parent's one page of everyone who looks after her child ({@link ChildTeamService}). Behind
 * `childTeam` — the flag the interceptor reads from the child in the path — and behind `chat` as well, which
 * {@link ChatService#placed} checks, so a school without chat has no team page either.
 *
 * <p><strong>No staff phone number is in the answer.</strong> {@link TeamMember} has no such field at all.
 */
@RestController
@FeatureFlag(FlagKeys.CHILD_TEAM)
@Tag(name = "Chat", description = "Parent and teacher chat: threads, message pages, sending and read receipts")
public class ChildTeamController {
    private final ChildTeamService team; private final Json json;
    public ChildTeamController(ChildTeamService team, Json json) { this.team = team; this.json = json; }

    @GetMapping(value = "/children/{id}/team", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.team')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = TeamMember.class))))
    public String childTeam(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return json.encodeShared(team.team(parent, id), BuiltinSerializersKt.ListSerializer(TeamMember.Companion.serializer()));
    }
}
