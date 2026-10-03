package quest.server.chat;

import java.util.List;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.stereotype.Component;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatThreadStatus;
import quest.api.dto.Complaint;
import quest.api.dto.ComplaintDetail;
import quest.api.dto.ComplaintList;
import quest.api.dto.ComplaintRecipient;
import quest.api.dto.ComplaintStatusRequest;
import quest.api.dto.CreateComplaintRequest;
import quest.api.dto.SendChatMessageRequest;
import quest.server.config.ApiException;
import quest.server.config.Json;

/**
 * B6: the Complaints routes' bodies through the shared kotlinx codec, in one place for the three controllers that
 * serve them ({@link ComplaintController}, `CoordinatorChatController`, `ManagementChatController`). An unreadable
 * body — a status that is neither `open` nor `resolved` included — is a 400 that says what to send.
 */
@Component
public class ComplaintCodec {
    private final Json json;
    public ComplaintCodec(Json json) { this.json = json; }

    private <T> T decode(String body, KSerializer<T> serializer, String shape) {
        try { return json.decodeShared(body, serializer); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send " + shape + "."); }
    }

    public CreateComplaintRequest create(String body) {
        return decode(body, CreateComplaintRequest.Companion.serializer(), "{\"staffId\": \"…\", \"title\": \"…\", \"body\": \"…\"}");
    }
    public SendChatMessageRequest message(String body) {
        return decode(body, SendChatMessageRequest.Companion.serializer(), "{\"body\": \"…\"} and, optionally, a clientId");
    }
    public ChatThreadStatus status(String body) {
        return decode(body, ComplaintStatusRequest.Companion.serializer(), "{\"status\": \"open\"} or {\"status\": \"resolved\"}").getStatus();
    }

    public String list(ComplaintList rows) { return json.encodeShared(rows, ComplaintList.Companion.serializer()); }
    public String detail(ComplaintDetail d) { return json.encodeShared(d, ComplaintDetail.Companion.serializer()); }
    public String row(Complaint c) { return json.encodeShared(c, Complaint.Companion.serializer()); }
    public String message(ChatMessage m) { return json.encodeShared(m, ChatMessage.Companion.serializer()); }
    public String receipt(ChatReadReceipt r) { return json.encodeShared(r, ChatReadReceipt.Companion.serializer()); }
    public String recipients(List<ComplaintRecipient> rows) {
        return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(ComplaintRecipient.Companion.serializer()));
    }
}
