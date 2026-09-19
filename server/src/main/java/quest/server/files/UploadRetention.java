package quest.server.files;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import quest.server.content.SourceFileRepository;

/** Uploaded slides live 24 hours (the GCS lifecycle rule does the same in production); page images and media stay. */
@Component
public class UploadRetention {
    private static final Logger log = LoggerFactory.getLogger(UploadRetention.class);
    private final SourceFileRepository sourceFiles; private final FileStore files;
    public UploadRetention(SourceFileRepository sourceFiles, FileStore files) { this.sourceFiles = sourceFiles; this.files = files; }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void sweep() {
        var cutoff = Instant.now().minus(24, ChronoUnit.HOURS); int n = 0;
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

    private void delete(String path) {
        try { files.delete(path); } catch (Exception e) { log.warn("could not delete {}: {}", path, e.toString()); }
    }
}
