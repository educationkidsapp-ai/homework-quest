package quest.server.translate;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.TranslateRequest;
import quest.api.dto.Translation;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 29's two doors. <strong>Both only ever add:</strong> there is no route here that writes a source row, and
 * the answer carries the original back beside the translation, so a client labels it and offers "Show original"
 * without a second request.
 *
 * <p>Its own controller rather than two more methods on the chat and broadcast controllers, because it is its own
 * feature and its own switch: `languageBridge` must be able to be off at a school that uses chat and announcements
 * every day, and a class-level flag on either of those would take the conversation itself with it.
 *
 * <p>Her reading language is {@code PUT /parent/me/reading-language} on {@code AuthController}, beside the rest of
 * her account: it is a preference she keeps whether or not the bridge is switched on today.
 */
@RestController
@FeatureFlag(FlagKeys.LANGUAGE_BRIDGE)
@Tag(name = "Language bridge", description = "Feature 29: a parent reads the school's messages in her own language, and staff read what she writes")
public class TranslationController {
    private final TranslationService translations; private final TranslationCodec json;
    public TranslationController(TranslationService translations, TranslationCodec json) {
        this.translations = translations; this.json = json;
    }

    /** One message, complaint message, broadcast or announcement of her own child's, in her own language. */
    @PostMapping(value = "/children/{id}/translate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.translate')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TranslateRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Translation.class)))
    public String translateForParent(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id,
                                     @RequestBody String body) {
        return json.translation(translations.forParent(parent, id, json.request(body)));
    }

    /** What a parent wrote, in English or Arabic — only from a thread or a complaint this caller may already read. */
    @PostMapping(value = "/staff/translate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.translate')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TranslateRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Translation.class)))
    public String translateForStaff(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        return json.translation(translations.forStaff(caller, json.request(body)));
    }
}
