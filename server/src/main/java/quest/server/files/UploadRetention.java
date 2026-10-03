package quest.server.files;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.springframework.data.domain.PageRequest;
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
    private final AttachmentRepository attachments;

    public UploadRetention(SourceFileRepository sourceFiles, FileStore files, AttachmentRepository attachments) {
        this.sourceFiles = sourceFiles; this.files = files; this.attachments = attachments;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void sweep() {
        sweepUploads();
        sweepAttachments();
    }

    /**
     * An `attachments` row no broadcast references, not sent with a chat message that still exists (B5), and older than
     * the grace period. <strong>All three matter:</strong> the references are the whole platform's (this runs from the
     * scheduler, where no `school` filter is enabled, so one school can never decide another's image is an orphan), and
     * the age is what protects an upload a composer is still holding — she uploads, then posts, and in between nothing
     * references it.
     *
     * <p>B5 review: the database picks the orphans ({@link AttachmentRepository#orphans}) a page at a time, rather than
     * this loading every attachment and every referenced id into memory. Each page is deleted before the next is asked
     * for, so the next one is always the first page; {@link #MAX_PAGES} bounds one run, and the next hour takes the rest.
     */
    private void sweepAttachments() {
        var cutoff = Instant.now().minus(GRACE_HOURS, ChronoUnit.HOURS);
        int n = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            var orphans = attachments.orphans(cutoff, PageRequest.of(0, PAGE));
            for (var a : orphans) {
                delete(a.getStoragePath());
                ImageInfo.copyPaths(a.getStoragePath()).forEach(this::delete);
                attachments.delete(a);
                n++;
            }
            attachments.flush();
            if (orphans.size() < PAGE) break;
        }
        if (n > 0) log.info("expired {} unreferenced attachment(s)", n);
    }

    private static final int PAGE = 200, MAX_PAGES = 50;

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
