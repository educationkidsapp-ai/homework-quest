package quest.server.chat;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.files.MediaController.Attachment;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * B5: the two upload routes for a chat message's files — one per side, because the flag is read from a different
 * place on each (a parent's from the child in the path, a staff member's from her school). Both answer MH1's
 * `AttachmentRef`, whose id the next send names in `attachmentIds`; the rules are {@link ChatAttachments}'.
 *
 * <p>They are their own routes rather than a `purpose` on `POST /media/attachments` so that route keeps its exact
 * shape: a parameter added to it moves the generated dashboard client's positional arguments under every caller.
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Chat", description = "Parent and teacher chat: threads, message pages, sending and read receipts")
public class ChatAttachmentController {
    private final ChatAttachments files;
    public ChatAttachmentController(ChatAttachments files) { this.files = files; }

    /** The parent's photo or PDF for a conversation about her child {@code id} — 404 for a child who is not hers. */
    @PostMapping(value = "/children/{id}/chat/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.chat')")
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadParentChatAttachment(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id,
                                                 @RequestPart("file") MultipartFile file) {
        return Attachment.of(files.uploadForParent(parent, id, file));
    }

    /** A staff member's, in her school; the Admin names hers with `X-School-Id`. */
    @PostMapping(value = "/media/chat-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('media.attachment.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadChatAttachment(@AuthenticationPrincipal Principals.User caller, @RequestPart("file") MultipartFile file) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return Attachment.of(files.uploadForStaff(caller, file));
    }
}
