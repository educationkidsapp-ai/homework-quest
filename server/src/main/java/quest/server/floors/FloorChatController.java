package quest.server.floors;

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
import quest.server.tenancy.FloorScope;

/**
 * Feature 20's Messages: the floor supervisor's half of the C1 chat — the same four things the coordinator's routes
 * do — with the parents of children on her floors, the teachers on them, the managers of the school and the Admin.
 * {@link ChatService} is the only place the rules live, so her send is checked exactly as a coordinator's is and both
 * land on the same socket.
 *
 * <p><strong>Named by thread, not by child.</strong> Her thread with a manager has no child on it at all, so every
 * route here takes the thread id and `ChatService.ownFloorThread` proves it is hers.
 *
 * <p><strong>Complaints are not hers.</strong> A complaint is addressed to a teacher, a coordinator or a manager
 * (B6's three areas) and feature 20's spec puts behaviour records and dismissal out of scope; there is no
 * `/floor/complaints` and `ComplaintService` has no FLOOR_SUPERVISOR area, so one of her floor's complaints reaches
 * the people it always did.
 *
 * <p><strong>The flag is `floorSupervisor`, not `chat`.</strong> The class is one feature's — hers — and
 * {@link ChatService#requireOn} already 404s every route here for a school that has `chat` off, exactly as it does on
 * the socket. One gate per route is the contract {@code FeatureFlagInterceptor} keeps.
 */
@RestController
@FeatureFlag(FlagKeys.FLOOR_SUPERVISOR)
@Tag(name = "Floor supervisor chat", description = "A floor supervisor's threads with the parents, teachers, managers and Admin of her floors")
public class FloorChatController {
    private final ChatService chat; private final FloorService floors; private final Json json;

    public FloorChatController(ChatService chat, FloorService floors, Json json) {
        this.chat = chat; this.floors = floors; this.json = json;
    }

    /** Whom `POST /floor/chat/threads` accepts besides a parent of her floor and a teacher on it. */
    @GetMapping(value = "/floor/chat/peers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.chat')")
    public FloorDto.FloorChatPeers floorChatPeers(@AuthenticationPrincipal Principals.User caller) {
        return floors.peers(FloorScope.require(caller));
    }

    @GetMapping(value = "/floor/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String floorChatThreads(@AuthenticationPrincipal Principals.User caller,
                                   @RequestParam(required = false) String status) {
        return json.encodeShared(chat.floorThreads(FloorScope.require(caller), status),
                BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer()));
    }

    /** Her thread with a parent of her floor, a teacher on it, a manager or the Admin — exactly one of the four. */
    @PostMapping(value = "/floor/chat/threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String floorChatThread(@AuthenticationPrincipal Principals.User caller,
                                  @RequestBody @Valid FloorDto.FloorThreadRequest body) {
        return json.encodeShared(chat.floorStaffThread(FloorScope.require(caller), body.childId(), body.teacherUserId(),
                body.managerUserId(), body.adminUserId()), ChatThread.Companion.serializer());
    }

    @GetMapping(value = "/floor/chat/threads/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String floorChatMessages(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                    @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                    @RequestParam(required = false) Integer limit) {
        return json.encodeShared(chat.floorMessages(FloorScope.require(caller), id, before, since, limit),
                BuiltinSerializersKt.ListSerializer(ChatMessage.Companion.serializer()));
    }

    @PostMapping(value = "/floor/chat/threads/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String floorSendChatMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                       @RequestBody String body) {
        var req = decode(body);
        return json.encodeShared(chat.floorSend(FloorScope.require(caller), id, req.getBody(), req.getAttachmentIds(), req.getClientId()),
                ChatMessage.Companion.serializer());
    }

    @PostMapping(value = "/floor/chat/threads/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String floorMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(chat.floorRead(FloorScope.require(caller), id), ChatReadReceipt.Companion.serializer());
    }

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId."); }
    }
}
