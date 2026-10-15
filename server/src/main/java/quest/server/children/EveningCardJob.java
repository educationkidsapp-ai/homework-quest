package quest.server.children;

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
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.SchoolRepository;

/**
 * Feature 28: from <strong>18:00 school time</strong>, every five minutes, for each school with `parentEveningCard`
 * on — one push to the parent of every child who has a card today, writing the cards that nobody has asked for yet.
 *
 * <p><strong>Once per child and day.</strong> Two claims make that true however often this runs and however many
 * instances run it: the card itself is V50's unique (school, child, day) row, and the parent's bell row is claimed on
 * that card's id ({@link NotificationService#parentEveningCard}). A rerun sends nothing new.
 *
 * <p><strong>Scope.</strong> The job has no caller and so no tenant ({@link quest.server.tenancy.TenantContext} is
 * null on the scheduler's thread): every query names the school, as {@link EveningCardRepository} and
 * {@link ChildRepository} require.
 *
 * <p>One child's failure is one child's: it is logged and the pass carries on, because a provider that refuses one
 * card must not cost a school its evening.
 */
@Component
public class EveningCardJob {
    private static final Logger log = LoggerFactory.getLogger(EveningCardJob.class);

    private final SchoolRepository schools; private final FeatureFlags flags; private final SchoolCalendar calendar;
    private final ChildRepository children; private final EveningCardService cards;
    private final NotificationService notifications; private final Clock clock; private final boolean enabled;

    public EveningCardJob(SchoolRepository schools, FeatureFlags flags, SchoolCalendar calendar, ChildRepository children,
                          EveningCardService cards, NotificationService notifications, Clock clock,
                          @Value("${quest.evening-card.job.enabled:true}") boolean enabled) {
        this.schools = schools; this.flags = flags; this.calendar = calendar; this.children = children;
        this.cards = cards; this.notifications = notifications; this.clock = clock; this.enabled = enabled;
    }

    /** On Cloud Run an instance wakes up after a quiet spell; an evening that fell due meanwhile is caught up at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void runAtStartup() { if (enabled) run(); }

    @Scheduled(fixedDelayString = "${quest.evening-card.job.interval-seconds:300}s",
               initialDelayString = "${quest.evening-card.job.interval-seconds:300}s")
    public void runPeriodically() { if (enabled) run(); }

    /** One pass over every school with the card on. Answers how many parents were told. */
    public int run() {
        int sent = 0;
        for (var school : schools.findAll()) {
            if (!on(school.getId())) continue;
            try { sent += run(school.getId()); }
            catch (RuntimeException e) { log.warn("evening card: school {} skipped this run: {}", school.getId(), e.toString()); }
        }
        return sent;
    }

    /** One school's pass, on the school's own today and nothing before {@link EveningCardService#PUSH_HOUR}. */
    public int run(String schoolId) {
        Instant now = clock.instant();
        var week = calendar.of(schoolId);
        LocalDate today = LocalDate.ofInstant(now, week.zone());
        if (!week.isSchoolDay(today)) return 0;
        if (now.isBefore(today.atTime(EveningCardService.PUSH_HOUR, 0).atZone(week.zone()).toInstant())) return 0;

        int sent = 0;
        for (var child : children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(schoolId)) {
            if (child.getParentId() == null) continue;                       // a roster row nobody has an account for
            Entities.EveningCardEntity card;
            try { card = cards.today(child, now); }
            catch (RuntimeException e) { log.warn("evening card: {} skipped this run: {}", child.getId(), e.toString()); continue; }
            if (card == null) continue;
            if (notifications.parentEveningCard(schoolId, child.getParentId(), child.getId(), child.getName(), card.getId(), today)) sent++;
        }
        return sent;
    }

    private boolean on(String schoolId) {
        try { return flags.isOn(schoolId, FlagKeys.PARENT_EVENING_CARD); }
        catch (RuntimeException e) { return false; }
    }
}
