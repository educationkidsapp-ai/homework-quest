package quest.server.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.ChatAttachment;
import quest.api.validation.SchemaValidator;
import quest.server.auth.Principals;
import quest.server.chat.Entities.ChatThreadEntity;
import quest.server.children.ChildRepository;
import quest.server.children.ChildService;
import quest.server.config.ApiException;
import quest.server.files.AttachmentRepository;
import quest.server.files.AttachmentService;
import quest.server.files.Entities.AttachmentEntity;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TenantContext;

/**
 * B5 (owner's report of 2026-10-03: "the app shows only the image's NAME, not the image"): real files on chat messages.
 * Until now an attachment was a `[attachment:…]` tag the sender's client wrote into the body, and nothing was uploaded.
 *
 * <p><strong>Three steps, three rules.</strong>
 * <ol>
 *   <li><strong>Upload</strong> — {@link ChatAttachmentController}: MH1's sniffing and limits (images ≤ 5 MB, a PDF
 *       ≤ 10 MB), into the caller's own school — a parent's is the school of the child in the path, who must be hers —
 *       and only while that school has `chat` on.</li>
 *   <li><strong>Send</strong> — {@link #claim}: each id named on the send must be the sender's own `chat` upload, in the
 *       thread's school, and not sent before; it is then bound to that one message (`attachments.message_id`) and its
 *       description is written onto the message row, so history and frames are built from the message alone.</li>
 *   <li><strong>Read</strong> — {@link #readable}: the bytes answer to the thread's participants — the staff member on
 *       `teacher_id`, the second one on `peer_user_id` (the admin included, on the threads she is on), and the parent of
 *       the child the thread is about — and to nobody else, whose answer is the same 404 an unknown id gets.</li>
 * </ol>
 * An upload that is never sent is MH1's orphan and is swept after 24 hours ({@code UploadRetention}).
 */
@Service
public class ChatAttachments {
    /** The most files one message carries. */
    public static final int MAX_PER_MESSAGE = 5;

    private final AttachmentRepository files; private final AttachmentService uploads; private final ChatMessageRepository messages;
    private final ChatThreadRepository threads; private final ChildRepository children; private final ChildService childService;
    private final FeatureFlags flags; private final TenantContext tenant;

    public ChatAttachments(AttachmentRepository files, AttachmentService uploads, ChatMessageRepository messages, ChatThreadRepository threads,
                           ChildRepository children, ChildService childService, FeatureFlags flags, TenantContext tenant) {
        this.files = files; this.uploads = uploads; this.messages = messages; this.threads = threads; this.children = children;
        this.childService = childService; this.flags = flags; this.tenant = tenant;
    }

    // ---------------------------------------------------------------- upload

    /** A parent's upload, filed under the school of {@code childId} — hers, or 404. */
    public AttachmentEntity uploadForParent(Principals.Parent parent, String childId, MultipartFile file) {
        var child = childService.owned(childId, parent);
        requireOn(child.getSchoolId());
        return uploads.store(child.getSchoolId(), parent.parentId(), file, quest.server.files.Entities.CHAT);
    }

    /** A staff member's upload, in her school; the Admin names hers with `X-School-Id`, as on every chat write of hers. */
    public AttachmentEntity uploadForStaff(Principals.User caller, MultipartFile file) {
        String schoolId = tenant.schoolId();
        if (schoolId == null) throw ApiException.badRequest("Send X-School-Id: a chat file belongs to one school's conversation.");
        requireOn(schoolId);
        return uploads.store(schoolId, caller.userId(), file, quest.server.files.Entities.CHAT);
    }

    // ---------------------------------------------------------------- send

    /**
     * Binds the named uploads to message {@code messageId} and answers their descriptions in the order they were named.
     * One `400` for every reason an id is not hers to send — not hers, another school's, a broadcast's, no such id — so
     * a send cannot be used to learn whether somebody else's upload exists; `409 attachment_already_sent` for her own
     * upload that already went with another message.
     *
     * <p>The bind itself is one conditional `UPDATE … WHERE message_id IS NULL` ({@link AttachmentRepository#bind}) and
     * its row count decides: two sends racing for one file both pass the read above, and only one of them updates the
     * row — the other is refused whole, and the transaction takes its message back with it.
     */
    List<ChatAttachment> claim(ChatThreadEntity thread, String senderId, String messageId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var wanted = ids.stream().filter(Objects::nonNull).map(String::trim).filter(id -> !id.isEmpty()).distinct().toList();
        if (wanted.size() > MAX_PER_MESSAGE) throw ApiException.badRequest("Send at most " + MAX_PER_MESSAGE + " files in one message.");
        var byId = new LinkedHashMap<String, AttachmentEntity>();
        files.findAllById(wanted).forEach(a -> byId.put(a.getId(), a));
        var out = new java.util.ArrayList<ChatAttachment>(wanted.size());
        for (String id : wanted) {
            var a = byId.get(id);
            if (a == null || !a.isChat() || !senderId.equals(a.getUploadedBy()) || !thread.getSchoolId().equals(a.getSchoolId()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "Upload the file to the chat upload route first — that id is not one you can send.");
            if (a.getMessageId() != null) throw alreadySent();
            out.add(new ChatAttachment(a.getId(), a.getMimeType(), a.getName(), a.getSizeBytes(), a.getWidth(), a.getHeight()));
        }
        if (files.bind(wanted, messageId, senderId, thread.getSchoolId()) != wanted.size()) throw alreadySent();
        return out;
    }

    private static ApiException alreadySent() {
        return ApiException.conflict("attachment_already_sent", "That file was already sent in another message — upload it again to send it twice.");
    }

    /** The `chat_messages.attachments` column: the list as JSON, or null when there is none. */
    static String encode(List<ChatAttachment> list) {
        return list.isEmpty() ? null : SchemaValidator.INSTANCE.getJson().encodeToString(LIST, list);
    }

    /** The column back, null when there is none (absent on the wire); an unreadable value is none rather than a failed page. */
    static List<ChatAttachment> decode(String column) {
        if (column == null || column.isBlank()) return null;
        try { return SchemaValidator.INSTANCE.getJson().decodeFromString(LIST, column); } catch (RuntimeException e) { return null; }
    }

    private static final kotlinx.serialization.KSerializer<List<ChatAttachment>> LIST = BuiltinSerializersKt.ListSerializer(ChatAttachment.Companion.serializer());

    /**
     * The one line a notification and a push say for a message: its text, or — files alone — "📷 Photo" / "📄 plan.pdf"
     * in English and "📷 صورة" / "📄 plan.pdf" in Arabic. The clients write the same line for a thread's `lastMessage`
     * from {@code attachments}, in the reader's language.
     */
    static String preview(String body, List<ChatAttachment> list, boolean arabic) {
        if (!body.isEmpty() || list.isEmpty()) return body;
        var first = list.getFirst();
        if (AttachmentService.PDF.equals(first.getContentType())) return "📄 " + first.getName();
        return arabic ? "📷 صورة" : "📷 Photo";
    }

    // ---------------------------------------------------------------- read

    /**
     * Whether the caller is a participant of the thread the attachment was sent in. The lookups run under the caller's
     * own scope: a dashboard user's are school-filtered (another school's message is simply absent), and a parent, who
     * has no filter, is checked against the child the thread names. The `chat` flag of the thread's school is checked
     * too, so a file is as unreachable as the conversation it is in while the feature is off.
     */
    public boolean readable(AttachmentEntity row, Principals.Parent parent, Principals.User user) {
        if (!row.isChat() || row.getMessageId() == null) return false;
        var thread = messages.findOneById(row.getMessageId()).flatMap(m -> threads.findOneById(m.getThreadId())).orElse(null);
        if (thread == null || !flags.isOn(thread.getSchoolId(), FlagKeys.CHAT)) return false;
        if (user != null) return user.userId().equals(thread.getTeacherId()) || user.userId().equals(thread.getPeerUserId());
        if (parent == null || thread.getChildId() == null) return false;
        return children.findOneById(thread.getChildId())
                .filter(c -> c.getDeletedAt() == null && parent.parentId().equals(c.getParentId())).isPresent();
    }

    private void requireOn(String schoolId) {
        if (!flags.isOn(schoolId, FlagKeys.CHAT)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }
}
