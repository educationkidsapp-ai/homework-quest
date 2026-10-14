package quest.server.chat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quest.api.dto.NotificationKind;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.SchoolRepository;

/**
 * Feature 19: one summary when a member of staff's working day starts — "You got 3 messages after hours" — for the
 * parent messages `chat_after_hours` collected while she was off ({@link NotificationService#chatMessage}).
 *
 * <p><strong>Once, whatever happens.</strong> A recipient's due rows are claimed in one statement
 * ({@link ChatAfterHoursRepository#claim}) and the summary is sent only when that statement claimed some, so a rerun,
 * a restart, or a second Cloud Run instance running the job at the same moment sends nothing twice. The count the
 * summary quotes is the number of rows claimed, which is the number of messages that arrived.
 *
 * <p><strong>When her day starts, not at midnight.</strong> A row falls due on her next working day
 * ({@link OfficeHours}), and the summary waits for that day's start to pass, so a teacher whose hours begin at 07:00
 * hears about last night at 07:00 and not at 00:05. A backlog from before today — a weekend, a holiday — goes out with
 * the same summary on the first working day that does start.
 *
 * <p><strong>Scope.</strong> The job has no caller and so no tenant ({@link quest.server.tenancy.TenantContext} is
 * null on the scheduler's thread): every query names the school, as feature 16's job does.
 */
@Component
public class OfficeHoursSummaryJob {
    private static final Logger log = LoggerFactory.getLogger(OfficeHoursSummaryJob.class);

    private final SchoolRepository schools; private final FeatureFlags flags; private final SchoolCalendar calendar;
    private final ChatAfterHoursRepository afterHours; private final OfficeHours officeHours;
    private final NotificationService notifications; private final Clock clock; private final boolean enabled;

    public OfficeHoursSummaryJob(SchoolRepository schools, FeatureFlags flags, SchoolCalendar calendar,
                                 ChatAfterHoursRepository afterHours, OfficeHours officeHours,
                                 NotificationService notifications, Clock clock,
                                 @Value("${quest.chat-office-hours.job.enabled:true}") boolean enabled) {
        this.schools = schools; this.flags = flags; this.calendar = calendar; this.afterHours = afterHours;
        this.officeHours = officeHours; this.notifications = notifications; this.clock = clock; this.enabled = enabled;
    }

    /** On Cloud Run an instance wakes up after a quiet spell; whatever fell due meanwhile is caught up at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void runAtStartup() { if (enabled) run(); }

    @Scheduled(fixedDelayString = "${quest.chat-office-hours.job.interval-seconds:300}s",
               initialDelayString = "${quest.chat-office-hours.job.interval-seconds:300}s")
    public void runPeriodically() { if (enabled) run(); }

    /** One pass over every school with office hours on. Answers how many summaries were sent. */
    public int run() {
        int sent = 0;
        for (var school : schools.findAll()) {
            if (!on(school.getId())) continue;
            try { sent += run(school.getId()); }
            catch (RuntimeException e) { log.warn("office hours: school {} skipped this run: {}", school.getId(), e.toString()); }
        }
        return sent;
    }

    /** One school's pass, on the school's own today. */
    int run(String schoolId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, calendar.of(schoolId).zone());
        int sent = 0;
        for (String userId : afterHours.dueRecipients(schoolId, today)) {
            if (!officeHours.status(schoolId, userId, now).startedToday()) continue;    // her day has not begun yet
            int count = afterHours.claim(schoolId, userId, today, now);
            if (count == 0) continue;                                                   // an earlier run has it
            notifications.staffNotice(schoolId, userId, NotificationKind.CHAT_AFTER_HOURS_SUMMARY,
                    "Messages after hours", english(count), "رسائل خارج الدوام", arabic(count),
                    notifications.afterHoursSummaryLink(userId, count), "after-hours:" + today);
            sent++;
        }
        return sent;
    }

    static String english(int count) {
        return "You got " + count + (count == 1 ? " message" : " messages") + " after hours";
    }

    /** Arabic counts the thing it counts: one message, two messages, a few of them, and many. */
    static String arabic(int count) {
        if (count == 1) return "وصلتك رسالة واحدة خارج الدوام";
        if (count == 2) return "وصلتك رسالتان خارج الدوام";
        if (count <= 10) return "وصلتك " + count + " رسائل خارج الدوام";
        return "وصلتك " + count + " رسالة خارج الدوام";
    }

    private boolean on(String schoolId) {
        try { return flags.isOn(schoolId, FlagKeys.CHAT_OFFICE_HOURS); }
        catch (RuntimeException e) { return false; }
    }
}
