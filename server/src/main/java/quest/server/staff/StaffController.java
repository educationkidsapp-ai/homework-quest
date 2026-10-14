package quest.server.staff;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatThread;
import quest.api.dto.SendChatMessageRequest;
import quest.api.staff.StaffMe;
import quest.api.staff.StaffPreferences;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 13: the five routes the staff app calls (`quest.api.staff.StaffApi`). Behind the `staffMobileApp` flag, so a
 * school that has not switched staff mode on answers 404 to all of them, and behind `staff.app`, which is the five
 * staff roles — a parent's Firebase token is 403 before any handler runs (`SecurityConfig`).
 *
 * <p><strong>A thin facade over the dashboard's own services, deliberately.</strong> The app's Messages tab speaks
 * {@link ChatThread}, {@link ChatMessage}, {@link SendChatMessageRequest} and {@link ChatReadReceipt} — the very
 * shapes the parent's and the teacher's halves of the chat already use — and every route hands the work to
 * {@link StaffService}, which hands it to `ChatService` under the caller's own role. One difference from the
 * dashboard and only one: a thread is always named by <strong>id</strong>, because the app shows a teacher's parent
 * threads and her staff threads in a single inbox and a single inbox cannot be keyed by child.
 *
 * <p>What is not here, because it already works with a staff token: typing and live frames (`/ws/chat`, the
 * `threadId` form of `ChatCommand`), uploads (`POST /media/chat-attachments`), the bell (`/me/notifications`) and the
 * phone (`POST /me/devices`).
 */
@RestController
@FeatureFlag(FlagKeys.STAFF_MOBILE_APP)
@Tag(name = "Staff app", description = "Staff mode in the mobile app: the signed-in member of staff, and her messages")
public class StaffController {
    private final StaffService staff; private final Json json;
    public StaffController(StaffService staff, Json json) { this.staff = staff; this.json = json; }

    @GetMapping(value = "/staff/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.app')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StaffMe.class)))
    public String staffMe(@AuthenticationPrincipal Principals.User caller) {
        return json.encodeShared(staff.me(require(caller)), StaffMe.Companion.serializer());
    }

    /** Feature 19: her own after-hours alerts choice. Behind the same flag and permission as `GET /staff/me`. */
    @PutMapping(value = "/staff/me/preferences", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.app')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StaffPreferences.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StaffMe.class)))
    public String staffPreferences(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        return json.encodeShared(staff.preferences(require(caller), preferences(body).getAfterHoursAlerts()), StaffMe.Companion.serializer());
    }

    @GetMapping(value = "/staff/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.app')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String staffChatThreads(@AuthenticationPrincipal Principals.User caller) {
        return json.encodeShared(staff.threads(require(caller)), BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer()));
    }

    @GetMapping(value = "/staff/chat/threads/{threadId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.app')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String staffChatMessages(@AuthenticationPrincipal Principals.User caller, @PathVariable String threadId,
                                    @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                    @RequestParam(required = false) Integer limit) {
        return json.encodeShared(staff.messages(require(caller), threadId, before, since, limit),
                BuiltinSerializersKt.ListSerializer(ChatMessage.Companion.serializer()));
    }

    @PostMapping(value = "/staff/chat/threads/{threadId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.app')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String staffSendChatMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String threadId, @RequestBody String body) {
        var req = decode(body);
        return json.encodeShared(staff.send(require(caller), threadId, req.getBody(), req.getAttachmentIds(), req.getClientId()),
                ChatMessage.Companion.serializer());
    }

    @PostMapping(value = "/staff/chat/threads/{threadId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('staff.app')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String staffMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String threadId) {
        return json.encodeShared(staff.read(require(caller), threadId), ChatReadReceipt.Companion.serializer());
    }

    private static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }

    private StaffPreferences preferences(String body) {
        try { return json.decodeShared(body, StaffPreferences.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"afterHoursAlerts\": true} or false."); }
    }

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, attachmentIds and a clientId."); }
    }
}
