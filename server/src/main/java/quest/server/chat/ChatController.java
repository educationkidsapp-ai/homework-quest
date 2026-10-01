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
 * teacher (`/teacher/chat/**`) and, read-only, the Admin doing support with `X-School-Id` (`/admin/chat/**`). R4's
 * coordinator half is {@code CoordinatorChatController}, which serves the same four things over these very methods
 * and lives under `/coordinator` so that `CoordinatorScopeArchitectureTest` sees it. The
 * live half is `/ws/chat` ({@link ChatSocketHandler}); both are behind the `chat` flag, and both encode the same
 * shared-api DTOs with the shared codec so the app and the dashboard read one shape.
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Chat", description = "Parent and teacher chat: threads, message pages, sending and read receipts")
public class ChatController {
    private final ChatService chat; private final Json json; private final StaffDirectory directory;
    public ChatController(ChatService chat, Json json, StaffDirectory directory) { this.chat = chat; this.json = json; this.directory = directory; }

    // ---------------------------------------------------------------- the parent (app)

    @GetMapping(value = "/children/{id}/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String parentChatThreads(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return threads(chat.parentThreads(parent, id));
    }

    /**
     * R4 (DR3): the coordinators of the subjects taught in her child's section, as thread rows — who she may open a
     * conversation with beyond the child's own teachers. `id` is null on a row nobody has written on yet.
     */
    @GetMapping(value = "/children/{id}/coordinators", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.coordinators')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String parentCoordinators(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return threads(chat.parentCoordinators(parent, id));
    }

    /**
     * RM2 (DR5): the manager of the department her child's section is in, as a thread row — whom she may write to about
     * the school, the child or a coordinator, `complaint` included. `id` is null until she writes the first message.
     */
    @GetMapping(value = "/children/{id}/managers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.managers')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String parentManagers(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return threads(chat.parentManagers(parent, id));
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
        return message(chat.parentSend(parent, id, teacherId, req.getBody(), req.getClientId(), req.getTopic()));
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

    // ---------------------------------------------------------------- the teacher's staff threads (MG1, DR5)

    /**
     * `GET /teacher/managers`: the managers of the departments she teaches in — whom the write below will accept — with
     * the contact details and the job in parts the owner asked for (T1). It answers `StaffDto.StaffContact`, the shape
     * `GET /coordinator/managers` and `GET /teacher/coordinators` answer, because it is one directory asked from two
     * roles and a second record with the same fields could only drift from the first.
     */
    @GetMapping(value = "/teacher/managers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    public List<StaffDto.StaffContact> teacherManagers(@AuthenticationPrincipal Principals.User caller) {
        return directory.managersForTeacher(caller);
    }

    /**
     * T1 `GET /teacher/coordinators`: her Coordinator page — the coordinators whose scope covers a (subject, track)
     * pair she teaches, each named with the grades of hers they cover ("Coordinator · Grade 1 · Math · British").
     * `teacher.chat`, the key her Messages screen already holds, because the list exists so that she can write to them.
     */
    @GetMapping(value = "/teacher/coordinators", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    public List<StaffDto.StaffContact> teacherCoordinators(@AuthenticationPrincipal Principals.User caller) {
        return directory.coordinatorsForTeacher(caller);
    }

    /**
     * Her conversations with those managers, keyed by thread id rather than by child: a staff thread has no child on
     * it, so `/teacher/chat/threads` above — which is keyed by child and always will be — could not carry one.
     */
    @GetMapping(value = "/teacher/chat/staff-threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String teacherStaffThreads(@AuthenticationPrincipal Principals.User caller) { return threads(chat.teacherStaffThreads(caller)); }

    /**
     * Her thread with one manager of a department she teaches in — or, since T1b, one coordinator of a subject she
     * teaches, which is the "direct message" button on her Coordinator page. Exactly one of the two ids; the same row
     * whichever side opens it, and the coordinator reads and answers it through her own `/coordinator/chat/**`.
     */
    @PostMapping(value = "/teacher/chat/staff-threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String teacherStaffThread(@AuthenticationPrincipal Principals.User caller, @RequestBody StaffDto.OpenStaffThreadRequest body) {
        return json.encodeShared(chat.teacherStaffThread(caller, body.managerUserId(), body.coordinatorUserId()), ChatThread.Companion.serializer());
    }

    @GetMapping(value = "/teacher/chat/staff-threads/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String teacherStaffMessages(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                       @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                       @RequestParam(required = false) Integer limit) {
        return messages(chat.teacherStaffMessages(caller, id, before, since, limit));
    }

    @PostMapping(value = "/teacher/chat/staff-threads/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String teacherSendStaffMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var req = decode(body);
        return message(chat.teacherStaffSend(caller, id, req.getBody(), req.getClientId()));
    }

    @PostMapping(value = "/teacher/chat/staff-threads/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String teacherMarkStaffRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return receipt(chat.teacherStaffRead(caller, id));
    }

    // ---------------------------------------------------------------- support (Admin, read-only)

    /**
     * Every thread of the school named by `X-School-Id`, newest first; `unread` is both sides' counts together.
     * S1: `?mine=true` is the admin's own inbox instead — only the threads she is on, with her own unread count.
     */
    @GetMapping(value = "/admin/chat/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('chat.support')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatThread.class))))
    public String supportChatThreads(@AuthenticationPrincipal Principals.User caller, @RequestParam(defaultValue = "false") boolean mine) {
        return threads(mine ? chat.adminThreads(caller) : chat.supportThreads());
    }

    @GetMapping(value = "/admin/chat/threads/{threadId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('chat.support')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
    public String supportChatMessages(@PathVariable String threadId, @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                      @RequestParam(required = false) Integer limit) {
        return messages(chat.supportMessages(threadId, before, since, limit));
    }

    /**
     * RM2 (DR5): the admin's own thread with one manager of the school she named. S1 widens it to exactly one of a
     * manager, a coordinator, a teacher or a child's registered parent; see {@link ChatService#adminThread}.
     */
    @PostMapping(value = "/admin/chat/threads", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatThread.class)))
    public String supportManagerThread(@AuthenticationPrincipal Principals.User caller, @RequestBody ManagerThreadRequest body) {
        return json.encodeShared(chat.adminThread(caller, body.managerUserId(), body.coordinatorUserId(), body.teacherUserId(), body.childId()),
                ChatThread.Companion.serializer());
    }

    /** `POST /admin/chat/threads` — exactly one of the four, in the school named by `X-School-Id`. */
    public record ManagerThreadRequest(String managerUserId, String coordinatorUserId, String teacherUserId, String childId) {}

    /** Into her own thread only: support reads every thread of a school, and writes into none but hers. */
    @PostMapping(value = "/admin/chat/threads/{threadId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String supportSendChatMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String threadId, @RequestBody String body) {
        var req = decode(body);
        return message(chat.adminSend(caller, threadId, req.getBody(), req.getClientId()));
    }

    @PostMapping(value = "/admin/chat/threads/{threadId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.chat')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String supportMarkChatRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String threadId) {
        return receipt(chat.adminRead(caller, threadId));
    }

    // ---------------------------------------------------------------- codec

    private SendChatMessageRequest decode(String body) {
        try { return json.decodeShared(body, SendChatMessageRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"body\": \"…\"} and, optionally, a clientId and a topic."); }
    }
    private String threads(List<ChatThread> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ChatThread.Companion.serializer())); }
    private String messages(List<ChatMessage> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ChatMessage.Companion.serializer())); }
    private String message(ChatMessage m) { return json.encodeShared(m, ChatMessage.Companion.serializer()); }
    private String receipt(ChatReadReceipt r) { return json.encodeShared(r, ChatReadReceipt.Companion.serializer()); }
}
