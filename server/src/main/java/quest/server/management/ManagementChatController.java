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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatThread;
import quest.api.dto.Complaint;
import quest.api.dto.ComplaintArea;
import quest.api.dto.ComplaintDetail;
import quest.api.dto.ComplaintList;
import quest.api.dto.ComplaintStatusRequest;
import quest.api.dto.SendChatMessageRequest;
import quest.server.auth.Principals;
import quest.server.chat.ChatService;
import quest.server.chat.ComplaintCodec;
import quest.server.chat.ComplaintService;
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

    /**
     * `POST /management/chat/threads`: one of four ids — a child of her department, whose registered parent she wants
     * to write to (MH1, owner's item 5), a teacher of it (MG1), a coordinator of it, or a platform admin. They are
     * read in that order when more than one is sent, and the body must name at least one.
     */
    public record StaffThreadRequest(String childId, String coordinatorUserId, String adminUserId, String teacherUserId) {}

    private final ChatService chat; private final Json json; private final ComplaintService complaints; private final ComplaintCodec codec;
    public ManagementChatController(ChatService chat, Json json, ComplaintService complaints, ComplaintCodec codec) {
        this.chat = chat; this.json = json; this.complaints = complaints; this.codec = codec;
    }

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
        return json.encodeShared(chat.managerSend(caller, id, req.getBody(), req.getAttachmentIds(), req.getClientId()), ChatMessage.Companion.serializer());
    }

    @PostMapping(value = "/management/chat/threads/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String managementMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(chat.managerRead(caller, id), ChatReadReceipt.Companion.serializer());
    }

    /**
     * Her thread with the parent of a child of her department, with a teacher or a coordinator of it, or with an admin;
     * one row whichever side opens it.
     */
    @PostMapping(value = "/management/chat/threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String managementStaffThread(@AuthenticationPrincipal Principals.User caller, @RequestBody StaffThreadRequest body) {
        return json.encodeShared(chat.managerStaffThread(caller, body.childId(), body.coordinatorUserId(), body.adminUserId(), body.teacherUserId()),
                ChatThread.Companion.serializer());
    }

    /** Whom `adminUserId` may name: the active platform admins. `GET /management/coordinators` is the other chooser. */
    @GetMapping(value = "/management/admins", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.chat')")
    public List<StaffPerson> managementAdmins(@AuthenticationPrincipal Principals.User caller) {
        return chat.admins(ManagerScope.require(caller)).stream().map(u -> new StaffPerson(u.getId(), ChatService.name(u))).toList();
    }

    // ---------------------------------------------------------------- B6: her Complaints page

    /**
     * B6: the complaints addressed to her and those she supervises (see `ComplaintService`), `?status=open|resolved|all`,
     * with the open and resolved counts whatever the filter. Complaints are their own conversations: none of them is
     * on her Messages list any more, and no Messages thread is on this one.
     */
    @GetMapping(value = "/management/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintList.class)))
    public String managementComplaints(@AuthenticationPrincipal Principals.User caller, @RequestParam(required = false) String status) {
        return codec.list(complaints.staffList(ComplaintArea.MANAGEMENT, caller, status));
    }

    @GetMapping(value = "/management/complaints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintDetail.class)))
    public String managementComplaint(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                        @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                        @RequestParam(required = false) Integer limit) {
        return codec.detail(complaints.staffDetail(ComplaintArea.MANAGEMENT, caller, id, before, since, limit));
    }

    /** Her reply, when the complaint is addressed to her; a complaint she supervises is 403 here (`canReply`). */
    @PostMapping(value = "/management/complaints/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.complaints')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String managementSendComplaintMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var req = codec.message(body);
        return codec.message(complaints.staffSend(ComplaintArea.MANAGEMENT, caller, id, req.getBody(), req.getClientId()));
    }

    @PostMapping(value = "/management/complaints/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String managementMarkComplaintRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return codec.receipt(complaints.staffRead(ComplaintArea.MANAGEMENT, caller, id));
    }

    /** `resolved` or `open` (reopen), on a complaint addressed to her or one she supervises. */
    @PatchMapping(value = "/management/complaints/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.complaints')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintStatusRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Complaint.class)))
    public String managementComplaintStatus(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        return codec.row(complaints.staffStatus(ComplaintArea.MANAGEMENT, caller, id, codec.status(body)));
    }

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId."); }
    }
}
