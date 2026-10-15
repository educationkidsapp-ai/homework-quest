package quest.server.documents;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ManagerScope;

/**
 * Feature 26's three doors: upload a circular, read back the last twenty, read back one.
 *
 * <p>Its own controller and its own switch, for {@code VoiceDraftController}'s reason: `documentReader` must be able
 * to be off at a school that posts announcements every day. <strong>There is no route that returns the file</strong>
 * and there never will be — it is not stored ({@link DocumentReaderService}) — and <strong>there is no route that
 * sends anything</strong>: "Draft announcement" opens the compose sheet the dashboard already has, and Send is
 * `POST /management/broadcasts`, unchanged.
 */
@RestController
@FeatureFlag(FlagKeys.DOCUMENT_READER)
@Tag(name = "Document reader", description = "Feature 26: a leader uploads a circular and reads what it says, in Arabic and English, with its dates and actions")
public class DocumentReaderController {
    /** One page of her own filing cabinet; the spec's "her recent summaries". */
    private static final int RECENT = 20;

    private final DocumentReaderService documents;
    public DocumentReaderController(DocumentReaderService documents) { this.documents = documents; }

    /**
     * The file in, one summary out. 400 when it is empty or not a PDF, a Word file or a JPEG, PNG or WebP image;
     * 413 `too_large` over 10 MB; 422 `too_long` over 20 pages, `unreadable_file` when no text comes out and
     * `rephrase` when the assistant cannot summarise it; 502 `model_failed` when the provider failed.
     */
    @PostMapping(value = "/management/documents/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('document.read')")
    public DocumentDto.View readDocument(@AuthenticationPrincipal Principals.User caller,
                                         @RequestPart("file") MultipartFile file) {
        return documents.read(ManagerScope.require(caller), file);
    }

    /** What this leader has read, newest first, at most twenty. */
    @GetMapping(value = "/management/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('document.read')")
    public List<DocumentDto.View> myDocumentSummaries(@AuthenticationPrincipal Principals.User caller) {
        return documents.mine(ManagerScope.require(caller), RECENT);
    }

    /** One of hers; another author's and another school's are both 404. */
    @GetMapping(value = "/management/documents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('document.read')")
    public DocumentDto.View documentSummary(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return documents.one(ManagerScope.require(caller), id);
    }
}
