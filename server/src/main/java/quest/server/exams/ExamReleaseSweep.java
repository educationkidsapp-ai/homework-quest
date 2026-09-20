package quest.server.exams;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;

/**
 * §8's "results release: <strong>automatic on close</strong>", which is the half of the release nobody is there to
 * perform: the window shuts at four o'clock on a Thursday and the parents are meant to see the result, whether or
 * not the teacher is still at her desk.
 *
 * <p>Modelled on {@link quest.server.analysis.PipelineWatchdog} down to the shape — a sweep every minute and one at
 * startup, for the leftovers of the instance this one replaced. On Cloud Run that startup pass is not an edge case:
 * the service scales to zero overnight, so the exam that closed at 16:00 is released by whichever instance wakes up
 * first rather than by a timer that died with the last one.
 *
 * <p><strong>Scope.</strong> The sweep has no caller and therefore no school ({@link quest.server.tenancy.TenantContext}
 * returns null on the scheduler's thread and the Hibernate filter stays off), exactly as the watchdog does: a window
 * closes for whichever school it belongs to. It reads by `release_mode` and `closes_at` and writes one column,
 * never anything a tenant could name.
 *
 * <p><strong>What it will not do.</strong> It never releases an exam that is not published — there is nothing to
 * show — and never one whose release a teacher has explicitly withdrawn (`release_withdrawn`, V13): a default is
 * not an argument against an instruction, and an automatic sweep that overruled her would be the worst possible
 * version of this feature. Switching the mode to `manual` is the other way to stop it.
 */
@Component
public class ExamReleaseSweep {
    private static final Logger log = LoggerFactory.getLogger(ExamReleaseSweep.class);

    private final ExamSettingsRepository settings; private final LessonRepository lessons;
    private final Clock clock; private final boolean enabled;

    public ExamReleaseSweep(ExamSettingsRepository settings, LessonRepository lessons, Clock clock,
                            @Value("${quest.exams.release-sweep.enabled:true}") boolean enabled) {
        this.settings = settings; this.lessons = lessons; this.clock = clock; this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepAtStartup() {
        if (!enabled) return;
        int n = sweep();
        if (n > 0) log.info("startup: released {} exam(s) whose window closed while nothing was running", n);
    }

    @Scheduled(fixedDelayString = "${quest.exams.release-sweep.interval-seconds:60}s",
               initialDelayString = "${quest.exams.release-sweep.interval-seconds:60}s")
    public void sweepPeriodically() { if (enabled) sweep(); }

    /** Releases every closed `auto_on_close` exam that is published and has not been released or withdrawn. */
    @Transactional
    public int sweep() {
        Instant now = clock.instant();
        int released = 0;
        for (var exam : settings.findByReleaseModeAndClosesAtLessThan(ExamLevels.AUTO_ON_CLOSE, now)) {
            var lesson = lessons.findById(exam.getLessonId()).orElse(null);
            if (!releasable(lesson)) continue;
            lesson.setReleasedAt(now);
            lesson.setUpdatedAt(now);
            lessons.save(lesson);
            released++;
            log.info("exam {} released automatically: its window closed at {}", lesson.getId(), exam.getClosesAt());
        }
        return released;
    }

    private static boolean releasable(LessonEntity lesson) {
        return lesson != null && "published".equals(lesson.getStatus())
                && lesson.getReleasedAt() == null && !lesson.isReleaseWithdrawn();
    }
}
