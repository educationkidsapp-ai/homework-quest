package quest.server.chat;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.ChannelPage;
import quest.api.dto.ChannelPost;
import quest.api.dto.ChannelPostRequest;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.files.MediaController.Attachment;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 32: the class channel ({@link ClassChannelService}) — the parent's two routes under her child, and the
 * staff side's four under the class. Behind `classChannel`, and behind `chat` as well, which the service checks.
 *
 * <p><strong>The Admin moderates on the staff routes.</strong> She reaches every class page already (D6, with
 * `X-School-Id`), so the channel needs no second set of routes of her own: `permissions.json` grants her
 * `channel.read` and `channel.moderate` and not `channel.post`, which is what makes her a reader and a moderator but
 * never an author in a teacher's name.
 *
 * <p><strong>Parents never post.</strong> There is no parent write route here beyond the read mark.
 */
@RestController
@FeatureFlag(FlagKeys.CLASS_CHANNEL)
@Tag(name = "Chat", description = "Parent and teacher chat: threads, message pages, sending and read receipts")
public class ClassChannelController {
    private final ClassChannelService channel; private final Json json;
    public ClassChannelController(ClassChannelService channel, Json json) { this.channel = channel; this.json = json; }

    // ---------------------------------------------------------------- the parent (app)

    @GetMapping(value = "/children/{id}/channel", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.channel')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChannelPage.class)))
    public String childChannel(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id,
                               @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                               @RequestParam(required = false) Integer limit) {
        return page(channel.parentChannel(parent, id, before, since, limit));
    }

    @PostMapping(value = "/children/{id}/channel/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.channel')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChannelPage.class)))
    public String markChildChannelRead(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return page(channel.markRead(parent, id));
    }

    // ---------------------------------------------------------------- the staff side (dashboard)

    @GetMapping(value = "/teacher/classes/{classId}/channel", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('channel.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChannelPage.class)))
    public String classChannel(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                               @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                               @RequestParam(required = false) Integer limit) {
        return page(channel.staffChannel(caller, classId, before, since, limit));
    }

    /** A photo or a PDF for the next post; its id goes in {@link ChannelPostRequest#getAttachmentIds()}. */
    @PostMapping(value = "/teacher/classes/{classId}/channel/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('channel.post')")
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadChannelAttachment(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                              @RequestPart("file") MultipartFile file) {
        return Attachment.of(channel.upload(caller, classId, file));
    }

    @PostMapping(value = "/teacher/classes/{classId}/channel/posts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('channel.post')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChannelPostRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChannelPost.class)))
    public String postToChannel(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId, @RequestBody String body) {
        return json.encodeShared(channel.post(caller, classId, decode(body)), ChannelPost.Companion.serializer());
    }

    @DeleteMapping("/teacher/classes/{classId}/channel/posts/{messageId}")
    @PreAuthorize("@permit.has('channel.moderate')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChannelPost(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId, @PathVariable String messageId) {
        channel.deletePost(caller, classId, messageId);
    }

    private String page(ChannelPage p) { return json.encodeShared(p, ChannelPage.Companion.serializer()); }

    private ChannelPostRequest decode(String body) {
        try { return json.decodeShared(body, ChannelPostRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\":\"…\"}: " + e.getMessage()); }
    }
}
