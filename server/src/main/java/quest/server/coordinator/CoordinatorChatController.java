package quest.server.coordinator;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/**
 * R4 (DR3): the coordinator's half of the C1 chat — the same four things the teacher's routes do — and, B6, her
 * Complaints page: complaints are their own conversations ({@link ComplaintService}), listed, answered and moved
 * between `open` and `resolved` on `/coordinator/complaints/**` and never on her Messages list. {@code ChatService} is the only place
 * the rules live, so a coordinator's send is checked exactly as a teacher's is and both land on the same socket.
 *
 * <p><strong>Named by thread, not by child.</strong> `/teacher/chat/threads/{childId}` works because a teacher's
 * conversations are all about one child each; a coordinator's are not — her thread with the manager of her department
 * has no child on it at all — so every route here takes the thread id and `ChatService.ownThread` proves it is hers.
 *
 * <p><strong>One flag, `chat`.</strong> A complaint is stored and delivered as a conversation (B6 gives it its own
 * thread, not its own store), so gating it on `complaints` instead would let a school collect complaints over the chat
 * it has on and then 404 the only screen that answers them. The `complaints` key stays for N5.2's own feature.
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Coordinator chat", description = "A coordinator's threads with parents and her manager, and her Complaints inbox")
public class CoordinatorChatController {
    private final ChatService chat; private final Json json; private final quest.server.chat.StaffDirectory directory;
    private final ComplaintService complaints; private final ComplaintCodec codec;

    public CoordinatorChatController(ChatService chat, Json json, quest.server.chat.StaffDirectory directory, ComplaintService complaints, ComplaintCodec codec) {
        this.chat = chat; this.json = json; this.directory = directory; this.complaints = complaints; this.codec = codec;
    }

    /**
     * `GET /coordinator/managers` (RM1 addendum): who `POST /coordinator/chat/threads` will accept — the managers whose
     * department intersects her scope, each with that department and — T1 — her address, her phone and her job in
     * parts, which is her Manager page. A record rather than the kotlinx codec, because there is no `Stop` in it and
     * springdoc needs a real schema for the generated client.
     *
     * <p>It resolves her scope through {@link quest.server.tenancy.CoordinatorScope#scopesOf}, inside
     * {@code StaffDirectory.managersForCoordinator}, so `CoordinatorScopeArchitectureTest` is satisfied by the same
     * call the thread-opening write already makes.
     */
    @GetMapping(value = "/coordinator/managers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    public List<quest.server.chat.StaffDto.StaffContact> coordinatorManagers(@AuthenticationPrincipal Principals.User caller) {
        return directory.managersForCoordinator(caller);
    }

    @GetMapping(value = "/coordinator/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String coordinatorChatThreads(@AuthenticationPrincipal Principals.User caller,
                                         @RequestParam(required = false) String status) {
        return threads(chat.coordinatorThreads(caller, status));
    }

    @GetMapping(value = "/coordinator/chat/threads/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String coordinatorChatMessages(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                          @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                          @RequestParam(required = false) Integer limit) {
        return json.encodeShared(chat.coordinatorMessages(caller, id, before, since, limit),
                BuiltinSerializersKt.ListSerializer(ChatMessage.Companion.serializer()));
    }

    @PostMapping(value = "/coordinator/chat/threads/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String coordinatorSendChatMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var req = decode(body);
        return json.encodeShared(chat.coordinatorSend(caller, id, req.getBody(), req.getAttachmentIds(), req.getClientId()), ChatMessage.Companion.serializer());
    }

    @PostMapping(value = "/coordinator/chat/threads/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String coordinatorMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(chat.coordinatorRead(caller, id), ChatReadReceipt.Companion.serializer());
    }

    /** Her thread with one manager of her department (DR5) or — S1 — one teacher of her subjects; exactly one id. */
    @PostMapping(value = "/coordinator/chat/threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String coordinatorStaffThread(@AuthenticationPrincipal Principals.User caller,
                                         @RequestBody @Valid CoordinatorDto.StaffThreadRequest body) {
        return json.encodeShared(chat.coordinatorStaffThread(caller, body.managerUserId(), body.teacherUserId()), ChatThread.Companion.serializer());
    }

    // ---------------------------------------------------------------- B6: her Complaints page

    /**
     * B6: the complaints addressed to her and those she supervises (see `ComplaintService`), `?status=open|resolved|all`,
     * with the open and resolved counts whatever the filter. Complaints are their own conversations: none of them is
     * on her Messages list any more, and no Messages thread is on this one.
     */
    @GetMapping(value = "/coordinator/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintList.class)))
    public String coordinatorComplaints(@AuthenticationPrincipal Principals.User caller, @RequestParam(required = false) String status) {
        return codec.list(complaints.staffList(ComplaintArea.COORDINATOR, caller, status));
    }

    @GetMapping(value = "/coordinator/complaints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintDetail.class)))
    public String coordinatorComplaint(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                        @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                        @RequestParam(required = false) Integer limit) {
        return codec.detail(complaints.staffDetail(ComplaintArea.COORDINATOR, caller, id, before, since, limit));
    }

    /** Her reply, when the complaint is addressed to her; a complaint she supervises is 403 here (`canReply`). */
    @PostMapping(value = "/coordinator/complaints/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String coordinatorSendComplaintMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var req = codec.message(body);
        return codec.message(complaints.staffSend(ComplaintArea.COORDINATOR, caller, id, req.getBody(), req.getClientId()));
    }

    @PostMapping(value = "/coordinator/complaints/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String coordinatorMarkComplaintRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return codec.receipt(complaints.staffRead(ComplaintArea.COORDINATOR, caller, id));
    }

    /** `resolved` or `open` (reopen), on a complaint addressed to her or one she supervises. */
    @PatchMapping(value = "/coordinator/complaints/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintStatusRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Complaint.class)))
    public String coordinatorComplaintStatus(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        return codec.row(complaints.staffStatus(ComplaintArea.COORDINATOR, caller, id, codec.status(body)));
    }

    private String threads(List<ChatThread> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer())); }

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId."); }
    }
}
