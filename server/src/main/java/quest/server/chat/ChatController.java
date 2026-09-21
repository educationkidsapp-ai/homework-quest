package quest.server.chat;

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
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * C1: the REST half of the chat — history, sending, read receipts — for the parent (`/children/{id}/chat/**`), the
 * teacher (`/teacher/chat/**`) and, read-only, the Admin doing support with `X-School-Id` (`/admin/chat/**`). The
 * live half is `/ws/chat` ({@link ChatSocketHandler}); both are behind the `chat` flag, and both encode the same
 * shared-api DTOs with the shared codec so the app and the dashboard read one shape.
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Chat", description = "Parent and teacher chat: threads, message pages, sending and read receipts")
public class ChatController {
    private final ChatService chat; private final Json json;
    public ChatController(ChatService chat, Json json) { this.chat = chat; this.json = json; }

    // ---------------------------------------------------------------- the parent (app)

    @GetMapping(value = "/children/{id}/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String parentChatThreads(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return threads(chat.parentThreads(parent, id));
    }

    @GetMapping(value = "/children/{id}/chat/threads/{teacherId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String parentChatMessages(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String teacherId,
                                     @RequestParam(required = false) String before, @RequestParam(required = false) String since, @RequestParam(required = false) Integer limit) {
        return messages(chat.parentMessages(parent, id, teacherId, before, since, limit));
    }

    @PostMapping(value = "/children/{id}/chat/threads/{teacherId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String parentSendChatMessage(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String teacherId, @RequestBody String body) {
        var req = decode(body);
        return message(chat.parentSend(parent, id, teacherId, req.getBody(), req.getClientId()));
    }

    @PostMapping(value = "/children/{id}/chat/threads/{teacherId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String parentMarkChatRead(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String teacherId) {
        return receipt(chat.parentRead(parent, id, teacherId));
    }

    // ---------------------------------------------------------------- the teacher (dashboard)

    @GetMapping(value = "/teacher/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String teacherChatThreads(@AuthenticationPrincipal Principals.User caller) { return threads(chat.teacherThreads(caller)); }

    @GetMapping(value = "/teacher/chat/threads/{childId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String teacherChatMessages(@AuthenticationPrincipal Principals.User caller, @PathVariable String childId,
                                      @RequestParam(required = false) String before, @RequestParam(required = false) String since, @RequestParam(required = false) Integer limit) {
        return messages(chat.teacherMessages(caller, childId, before, since, limit));
    }

    @PostMapping(value = "/teacher/chat/threads/{childId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String teacherSendChatMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String childId, @RequestBody String body) {
        var req = decode(body);
        return message(chat.teacherSend(caller, childId, req.getBody(), req.getClientId()));
    }

    @PostMapping(value = "/teacher/chat/threads/{childId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String teacherMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String childId) { return receipt(chat.teacherRead(caller, childId)); }

    // ---------------------------------------------------------------- support (Admin, read-only)

    /** Every thread of the school named by `X-School-Id`, newest first; `unread` is both sides' counts together. */
    @GetMapping(value = "/admin/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('chat.support')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String supportChatThreads() { return threads(chat.supportThreads()); }

    @GetMapping(value = "/admin/chat/threads/{threadId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('chat.support')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String supportChatMessages(@PathVariable String threadId, @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                      @RequestParam(required = false) Integer limit) {
        return messages(chat.supportMessages(threadId, before, since, limit));
    }

    // ---------------------------------------------------------------- codec

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId."); }
    }
    private String threads(List<ChatThread> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer())); }
    private String messages(List<ChatMessage> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ChatMessage.Companion.serializer())); }
    private String message(ChatMessage m) { return json.encodeShared(m, ChatMessage.Companion.serializer()); }
    private String receipt(ChatReadReceipt r) { return json.encodeShared(r, ChatReadReceipt.Companion.serializer()); }
}
