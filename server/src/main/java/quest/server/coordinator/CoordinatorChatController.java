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
import quest.api.dto.SendChatMessageRequest;
import quest.server.auth.Principals;
import quest.server.chat.ChatService;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * R4 (DR3): the coordinator's half of the C1 chat — the same four things the teacher's routes do, plus the Complaints
 * inbox and the one write that moves a thread between `open` and `resolved`. {@code ChatService} is the only place
 * the rules live, so a coordinator's send is checked exactly as a teacher's is and both land on the same socket.
 *
 * <p><strong>Named by thread, not by child.</strong> `/teacher/chat/threads/{childId}` works because a teacher's
 * conversations are all about one child each; a coordinator's are not — her thread with the manager of her department
 * has no child on it at all — so every route here takes the thread id and `ChatService.ownThread` proves it is hers.
 *
 * <p><strong>One flag, `chat`.</strong> A complaint <em>is</em> a chat thread (DR3: "no separate complaints server in
 * this phase"), so gating this inbox on `complaints` instead would let a school collect complaint threads over the
 * chat it has on and then 404 the only screen that answers them. The `complaints` key stays for N5.2's own feature,
 * and what the coordinator's area shows of a flagged feature keeps that feature's flag, as R3's reads do.
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Coordinator chat", description = "A coordinator's threads with parents and her manager, and her Complaints inbox")
public class CoordinatorChatController {
    private final ChatService chat; private final Json json; private final quest.server.chat.ChatPeers peers;

    public CoordinatorChatController(ChatService chat, Json json, quest.server.chat.ChatPeers peers) {
        this.chat = chat; this.json = json; this.peers = peers;
    }

    /**
     * `GET /coordinator/managers` (RM1 addendum): who `POST /coordinator/chat/threads` will accept — the managers whose
     * department intersects her scope, each with that department. A record rather than the kotlinx codec, because there
     * is no `Stop` in it and springdoc needs a real schema for the generated client.
     *
     * <p>It resolves her scope through {@link quest.server.tenancy.CoordinatorScope#scopesOf}, inside
     * {@code ChatPeers.managerOptionsFor}, so `CoordinatorScopeArchitectureTest` is satisfied by the same call the
     * thread-opening write already makes.
     */
    @GetMapping(value = "/coordinator/managers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    public List<CoordinatorDto.CoordinatorManager> coordinatorManagers(@AuthenticationPrincipal Principals.User caller) {
        return peers.managerOptionsFor(quest.server.tenancy.CoordinatorScope.require(caller)).stream()
                .map(m -> new CoordinatorDto.CoordinatorManager(m.user().getId(), ChatService.name(m.user()), m.curriculum()))
                .toList();
    }

    @GetMapping(value = "/coordinator/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String coordinatorChatThreads(@AuthenticationPrincipal Principals.User caller,
                                         @RequestParam(required = false) String status) {
        return threads(chat.coordinatorThreads(caller, null, status));
    }

    /** Her Complaints inbox: the `complaint` threads in scope, `?status=open` while she is working through them. */
    @GetMapping(value = "/coordinator/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String coordinatorComplaints(@AuthenticationPrincipal Principals.User caller,
                                        @RequestParam(required = false) String status) {
        return threads(chat.coordinatorThreads(caller, ChatService.COMPLAINT, status));
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
        return json.encodeShared(chat.coordinatorSend(caller, id, req.getBody(), req.getClientId()), ChatMessage.Companion.serializer());
    }

    @PostMapping(value = "/coordinator/chat/threads/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String coordinatorMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(chat.coordinatorRead(caller, id), ChatReadReceipt.Companion.serializer());
    }

    /** Her thread with one manager of her own department (DR5); the same thread whichever of the two opens it. */
    @PostMapping(value = "/coordinator/chat/threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String coordinatorStaffThread(@AuthenticationPrincipal Principals.User caller,
                                         @RequestBody @Valid CoordinatorDto.StaffThreadRequest body) {
        return json.encodeShared(chat.coordinatorStaffThread(caller, body.managerUserId()), ChatThread.Companion.serializer());
    }

    /** `open` / `resolved` on a complaint. The parent is told over her own socket with a `status` frame. */
    @PatchMapping(value = "/coordinator/chat/threads/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String coordinatorThreadStatus(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                          @RequestBody @Valid CoordinatorDto.ThreadStatusRequest body) {
        return json.encodeShared(chat.coordinatorStatus(caller, id, body.status()), ChatThread.Companion.serializer());
    }

    private String threads(List<ChatThread> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer())); }

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId."); }
    }
}
