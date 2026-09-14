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
            try { files.delete(f.getStoragePath()); } catch (Exception e) { log.warn("could not delete {}: {}", f.getStoragePath(), e.toString()); }
            f.setDeletedAt(Instant.now()); sourceFiles.save(f); n++;
        }
        if (n > 0) log.info("expired {} uploaded file(s)", n);
    }
}
