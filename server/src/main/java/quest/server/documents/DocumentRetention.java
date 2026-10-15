package quest.server.documents;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 26's retention sweep, in {@code VoiceDraftRetention}'s shape: a summary is deleted thirty days after it was
 * read. A circular is somebody else's business that passed through the school for a week — a ministry deadline, a
 * letter about one family — and keeping the read of it for ever is keeping a copy of the document in all but name.
 * Thirty days is long enough for a term's worth of deadlines to have been acted on and for the dashboard list to be
 * useful; after that she uploads it again.
 *
 * <p>Unscoped, for {@code VoiceDraftRetention}'s reason — it runs from the scheduler, where no `school` filter is
 * enabled, so one school never decides what another school's leader still has open. The clock is injected rather than
 * {@code Instant.now()}, which is what lets a test pin a summary a month into the past and call {@link #sweep()}
 * directly.
 */
@Component
public class DocumentRetention {
    private static final Logger log = LoggerFactory.getLogger(DocumentRetention.class);
    private static final int KEEP_DAYS = 30;
    private static final int PAGE = 200, MAX_PAGES = 50;

    private final DocumentSummaryRepository summaries; private final Clock clock;

    public DocumentRetention(DocumentSummaryRepository summaries, Clock clock) { this.summaries = summaries; this.clock = clock; }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT11M")
    @Transactional
    public void sweep() {
        var cutoff = clock.instant().minus(KEEP_DAYS, ChronoUnit.DAYS);
        int n = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            var stale = summaries.olderThan(cutoff, PageRequest.of(0, PAGE));
            if (stale.isEmpty()) break;
            summaries.deleteAll(stale);
            summaries.flush();
            n += stale.size();
            if (stale.size() < PAGE) break;
        }
        if (n > 0) log.info("deleted {} document summary(ies) older than {} days", n, KEEP_DAYS);
    }
}
