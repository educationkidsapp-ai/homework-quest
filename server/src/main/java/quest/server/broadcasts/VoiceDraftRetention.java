package quest.server.broadcasts;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 22's privacy sweep, in {@link quest.server.files.UploadRetention}'s shape: a draft nobody sent is deleted
 * after a week. The spec keeps the transcript "only with the announcement draft", and a draft the leader thought
 * better of is a recording of her voice in text form that no screen will ever show again.
 *
 * <p>A sent draft is <em>not</em> swept: it is the transcript of an announcement that went out, and it stays with it.
 * `broadcast_id` is the whole rule.
 *
 * <p>Unscoped, for {@code UploadRetention.sweepAttachments}'s reason — it runs from the scheduler, where no `school`
 * filter is enabled, so one school never decides what another school's leader still has open. The clock is injected
 * rather than {@code Instant.now()}, which is what lets a test pin a draft a week into the past and call
 * {@link #sweep()} directly.
 */
@Component
public class VoiceDraftRetention {
    private static final Logger log = LoggerFactory.getLogger(VoiceDraftRetention.class);
    /** Long enough that a leader may leave a preview open over a weekend and still press Send on Sunday. */
    private static final int KEEP_DAYS = 7;
    private static final int PAGE = 200, MAX_PAGES = 50;

    private final AnnouncementDraftRepository drafts; private final Clock clock;

    public VoiceDraftRetention(AnnouncementDraftRepository drafts, Clock clock) { this.drafts = drafts; this.clock = clock; }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT9M")
    @Transactional
    public void sweep() {
        var cutoff = clock.instant().minus(KEEP_DAYS, ChronoUnit.DAYS);
        int n = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            var stale = drafts.unsent(cutoff, PageRequest.of(0, PAGE));
            if (stale.isEmpty()) break;
            drafts.deleteAll(stale);
            drafts.flush();
            n += stale.size();
            if (stale.size() < PAGE) break;
        }
        if (n > 0) log.info("deleted {} unsent announcement draft(s)", n);
    }
}
