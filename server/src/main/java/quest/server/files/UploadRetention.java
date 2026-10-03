package quest.server.files;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import quest.server.content.SourceFileRepository;

/**
 * Uploaded slides live 24 hours (the GCS lifecycle rule does the same in production); page images and media stay.
 *
 * <p>MH1 adds the second half: a broadcast <strong>attachment</strong> nothing points at. `V24` promised this sweep and
 * did not have it, and `BroadcastService.replacePlan` deletes the superseded weekly plan — so a plan re-posted each
 * week left one image per post in the bucket for ever. `replacePlan` now reclaims its own immediately; this is the
 * backstop for everything that route does not see: an upload nobody ever attached, and a row deleted anywhere else.
 */
@Component
public class UploadRetention {
    private static final Logger log = LoggerFactory.getLogger(UploadRetention.class);
    private final SourceFileRepository sourceFiles; private final FileStore files;
    private final AttachmentRepository attachments; private final quest.server.broadcasts.BroadcastRepository broadcasts;

    public UploadRetention(SourceFileRepository sourceFiles, FileStore files, AttachmentRepository attachments,
                           quest.server.broadcasts.BroadcastRepository broadcasts) {
        this.sourceFiles = sourceFiles; this.files = files; this.attachments = attachments; this.broadcasts = broadcasts;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void sweep() {
        sweepUploads();
        sweepAttachments();
    }

    /**
     * An `attachments` row no broadcast references and older than the grace period. <strong>Both halves matter:</strong>
     * the reference set is the whole platform's (this runs from the scheduler, where no `school` filter is enabled, so
     * one school can never decide another's image is an orphan), and the age is what protects an upload a composer is
     * still holding — she uploads the image, then posts, and in between nothing references it.
     */
    private void sweepAttachments() {
        var cutoff = Instant.now().minus(GRACE_HOURS, ChronoUnit.HOURS);
        var referenced = broadcasts.referencedAttachmentIds();
        // B5: a chat file is kept while the message it was sent with exists; one never sent, or whose message has
        // gone with its thread, is an orphan like any other.
        var sent = attachments.sentWithLiveMessage();
        int n = 0;
        for (var a : attachments.findAll()) {
            if (referenced.contains(a.getId()) || sent.contains(a.getId()) || !a.getCreatedAt().isBefore(cutoff)) continue;
            delete(a.getStoragePath());
            attachments.delete(a);
            n++;
        }
        if (n > 0) log.info("expired {} unreferenced attachment(s)", n);
    }

    /** The slides half, unchanged. */
    private void sweepUploads() {
        var cutoff = Instant.now().minus(GRACE_HOURS, ChronoUnit.HOURS); int n = 0;
        for (var f : sourceFiles.findAll()) if (f.getDeletedAt() == null && f.getCreatedAt().isBefore(cutoff)) {
            delete(f.getStoragePath());
            // CR4: the Markdown we extracted is as much a copy of the upload as the upload is, and it lives a day
            // beside it. Leaving it behind would keep a school's lesson text in the bucket after the file it came
            // from was swept, and leave a row pointing at a blob the preview would 404 on.
            if (f.getMarkdownPath() != null) delete(f.getMarkdownPath());
            f.clearMarkdown();
            f.setDeletedAt(Instant.now()); sourceFiles.save(f); n++;
        }
        if (n > 0) log.info("expired {} uploaded file(s)", n);
    }

    /** Long enough that an upload still open in a composer is never swept from under it. */
    private static final int GRACE_HOURS = 24;

    private void delete(String path) {
        try { files.delete(path); } catch (Exception e) { log.warn("could not delete {}: {}", path, e.toString()); }
    }
}
