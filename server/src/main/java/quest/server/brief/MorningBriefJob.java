package quest.server.brief;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quest.api.dto.NotificationKind;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * Feature 25: every five minutes, for each school with `morningBrief` on, writes the day's brief for every Admin and
 * every department manager once the school's own morning has reached {@link #FROM} — and rings her bell.
 *
 * <p><strong>Once, whatever happens.</strong> The brief is claimed in `morning_briefs` under (school, user, day)
 * before the model is called ({@link MorningBriefRepository#claim}), so a rerun writes nothing, a second Cloud Run
 * instance waking at the same minute writes nothing, and the bell rings for the run whose insert won and for no
 * other. A leader who opens her Home before the job reaches her generates her own through the very same claim
 * ({@link MorningBriefService#brief}) and is simply not told twice.
 *
 * <p><strong>School days only, in the school's own zone</strong> ({@link SchoolCalendar}): there is no morning to
 * brief on a Friday or on a holiday the school has declared (feature 14), and yesterday's numbers are the previous
 * <em>teaching</em> day's — {@link MorningBriefService#teachingDay} decides both.
 *
 * <p><strong>Scope.</strong> The job has no caller, so it becomes each leader for the length of her own brief —
 * {@link TenantContext#set} with her role and her school, as `SchoolSeed` does — and clears it again. That is what
 * makes a manager's brief her academy's: every service the facts are read from narrows by the principal it is handed,
 * and handing it a principal is the only way to ask them the question a leader asks.
 */
@Component
public class MorningBriefJob {
    private static final Logger log = LoggerFactory.getLogger(MorningBriefJob.class);

    /** The spec's 07:15, in the school's own zone: after the start of the day, before the first lesson. */
    static final LocalTime FROM = LocalTime.of(7, 15);

    private final SchoolRepository schools; private final UserRepository users; private final FeatureFlags flags;
    private final SchoolCalendar calendar; private final MorningBriefService briefs;
    private final NotificationService notifications; private final TenantContext tenant; private final Clock clock;
    private final boolean enabled;

    public MorningBriefJob(SchoolRepository schools, UserRepository users, FeatureFlags flags, SchoolCalendar calendar,
                           MorningBriefService briefs, NotificationService notifications, TenantContext tenant,
                           Clock clock, @Value("${quest.morning-brief.job.enabled:true}") boolean enabled) {
        this.schools = schools; this.users = users; this.flags = flags; this.calendar = calendar; this.briefs = briefs;
        this.notifications = notifications; this.tenant = tenant; this.clock = clock; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${quest.morning-brief.job.interval-seconds:300}s",
               initialDelayString = "${quest.morning-brief.job.interval-seconds:300}s")
    public void runPeriodically() { if (enabled) run(); }

    /** One pass over every school with the brief on. Answers how many briefs this run wrote. */
    public int run() {
        int written = 0;
        for (var school : schools.findAll()) {
            try { written += run(school.getId()); }
            catch (RuntimeException e) { log.warn("morning brief: school {} skipped this run: {}", school.getId(), e.toString()); }
        }
        return written;
    }

    /** One school's pass, on the school's own today. The switch is read here, so a school with it off is never briefed. */
    int run(String schoolId) {
        if (!on(schoolId)) return 0;
        var week = calendar.of(schoolId);
        LocalDate today = LocalDate.ofInstant(clock.instant(), week.zone());
        // The week's own days off *and* feature 14's declared holidays: an Eid morning has no brief either.
        if (!briefs.teachingDay(schoolId, week, today)) return 0;
        if (clock.instant().isBefore(today.atTime(FROM).atZone(week.zone()).toInstant())) return 0;

        int written = 0;
        for (var leader : leaders(schoolId)) {
            try {
                tenant.set(leader.role(), leader.schoolId(), schoolId);
                if (!briefs.writeIfAbsent(schoolId, leader, today)) continue;
                ring(schoolId, leader, today);
                written++;
            } catch (RuntimeException e) {
                log.warn("morning brief: {} skipped this run: {}", leader.userId(), e.toString());
            } finally {
                tenant.clear();
            }
        }
        return written;
    }

    /**
     * Whose brief it is: every active platform Admin (she has no `school_id` of her own and scopes with the header,
     * D6) and every active department manager of this school. Read before the loop sets a tenant, so the manager
     * query runs unfiltered and names the school itself, as every query of a job does.
     */
    private List<Principals.User> leaders(String schoolId) {
        var out = new ArrayList<Principals.User>();
        for (String adminId : users.findActiveAdminIds())
            out.add(new Principals.User(adminId, "", "ADMIN", null));
        for (var manager : users.findBySchoolIdAndRole(schoolId, ManagerScope.ROLE))
            if ("active".equals(manager.getStatus())) out.add(new Principals.User(manager.getId(), manager.getEmail(), ManagerScope.ROLE, schoolId));
        return out;
    }

    /**
     * The bell, and the staff push when `staffMobileApp` is on ({@link NotificationService#staffNotice}). The row
     * carries no number of its own: the lines are read from `GET /me/morning-brief`, so a brief whose wording the
     * model replaced cannot be contradicted by a notification written before it.
     */
    private void ring(String schoolId, Principals.User leader, LocalDate day) {
        notifications.staffNotice(schoolId, leader.userId(), NotificationKind.MORNING_BRIEF,
                "Your morning brief", "Today's brief is ready.",
                "موجز صباحك", "موجز اليوم جاهز",
                NotificationService.morningBriefLink(), "morning-brief:" + day);
    }

    private boolean on(String schoolId) {
        try { return flags.isOn(schoolId, FlagKeys.MORNING_BRIEF); }
        catch (RuntimeException e) { return false; }
    }
}
