package quest.server.management;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatThread;
import quest.api.dto.SendChatMessageRequest;
import quest.server.auth.Principals;
import quest.server.chat.ChatService;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ManagerScope;

/**
 * RM2 (DR5): the manager's half of the C1 chat — the parents of her department, the coordinators she manages and the
 * admin she reports to. {@code CoordinatorChatController}'s mirror one scope over, and for the same reasons: every
 * route names the <em>thread</em> rather than a child (two of her three kinds of thread have no child on them), and
 * {@link ChatService} is the only place the rules live, so her send is checked exactly as a coordinator's is and both
 * land on the same socket.
 *
 * <p>It sits in this package, with the rest of `/management`, so `ManagerScopeArchitectureTest` sees it: every handler
 * below reaches {@link ManagerScope} — `reach` for the list, `requireChild` for a parent thread, `coordinatorsOf` for
 * the one write that opens a staff thread — and the four writes are named in that test's allow-list.
 *
 * <p><strong>One flag, `chat`</strong>, as the coordinator's inbox carries: a school without chat has no Messages
 * screen for either role, and `/management/**` itself carries none because it is the dashboard of a role rather than a
 * feature ({@code ManagementController}).
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Management chat", description = "A manager's threads with the parents of her department, her coordinators and the admin")
public class ManagementChatController {
    /** A person a manager may open a thread with by id — the platform admins (`GET /management/admins`). */
    public record StaffPerson(String userId, String displayName) {}

    /** `POST /management/chat/threads`: one of the two ids, never both — a coordinator of her department, or an admin. */
    public record StaffThreadRequest(String coordinatorUserId, String adminUserId) {}

    private final ChatService chat; private final Json json;
    public ManagementChatController(ChatService chat, Json json) { this.chat = chat; this.json = json; }

    @GetMapping(value = "/management/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String managementChatThreads(@AuthenticationPrincipal Principals.User caller,
                                        @RequestParam(required = false) String status) {
        return json.encodeShared(chat.managerThreads(caller, status), BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer()));
    }

    @GetMapping(value = "/management/chat/threads/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String managementChatMessages(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                         @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                         @RequestParam(required = false) Integer limit) {
        return json.encodeShared(chat.managerMessages(caller, id, before, since, limit),
                BuiltinSerializersKt.ListSerializer(ChatMessage.Companion.serializer()));
    }

    @PostMapping(value = "/management/chat/threads/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String managementSendChatMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var req = decode(body);
        return json.encodeShared(chat.managerSend(caller, id, req.getBody(), req.getClientId()), ChatMessage.Companion.serializer());
    }

    @PostMapping(value = "/management/chat/threads/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String managementMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(chat.managerRead(caller, id), ChatReadReceipt.Companion.serializer());
    }

    /** Her thread with a coordinator of her department or with an admin; the same thread whichever side opens it. */
    @PostMapping(value = "/management/chat/threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String managementStaffThread(@AuthenticationPrincipal Principals.User caller, @RequestBody StaffThreadRequest body) {
        return json.encodeShared(chat.managerStaffThread(caller, body.coordinatorUserId(), body.adminUserId()),
                ChatThread.Companion.serializer());
    }

    /** Whom `adminUserId` may name: the active platform admins. `GET /management/coordinators` is the other chooser. */
    @GetMapping(value = "/management/admins", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    public List<StaffPerson> managementAdmins(@AuthenticationPrincipal Principals.User caller) {
        return chat.admins(ManagerScope.require(caller)).stream().map(u -> new StaffPerson(u.getId(), ChatService.name(u))).toList();
    }

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId."); }
    }
}
