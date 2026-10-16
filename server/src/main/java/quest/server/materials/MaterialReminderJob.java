package quest.server.materials;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quest.api.dashboard.MaterialLink;
import quest.api.dto.NotificationKind;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.materials.Entities.MaterialReminderEntity;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.StaffAttendanceDto;
import quest.server.staffattendance.StaffBoardScope;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 31: every five minutes, for each school with `teachingMaterials` on, reminds the teachers whose teaching
 * material for the <strong>coming</strong> week is still missing — from `remind_hours_before` before the deadline
 * until the deadline itself, and never after it.
 *
 * <p><strong>Once, whatever happens.</strong> Each reminder is claimed in `material_reminders` under (school,
 * teacher, week) with ON CONFLICT DO NOTHING, and is sent only by the run whose insert wrote the row. A rerun sends
 * nothing new, and neither does a second Cloud Run instance running the job at the same moment — feature 16's rule
 * exactly ({@link quest.server.staffattendance.StaffAttendanceJob}).
 *
 * <p><strong>Scope.</strong> The job has no caller and so no tenant ({@link quest.server.tenancy.TenantContext} is
 * null on the scheduler's thread): every query names the school, as every repository of this package requires.
 */
@Component
public class MaterialReminderJob {
    private static final Logger log = LoggerFactory.getLogger(MaterialReminderJob.class);

    private final SchoolRepository schools; private final FeatureFlags flags; private final SchoolCalendar calendar;
    private final MaterialDeadlines deadlines; private final TeacherScope teacherScope;
    private final MaterialSubmissionRepository submissions; private final MaterialReminderRepository reminders;
    private final StaffBoardScope staff; private final NotificationService notifications;
    private final Clock clock; private final boolean enabled;

    public MaterialReminderJob(SchoolRepository schools, FeatureFlags flags, SchoolCalendar calendar,
                               MaterialDeadlines deadlines, TeacherScope teacherScope,
                               MaterialSubmissionRepository submissions, MaterialReminderRepository reminders,
                               StaffBoardScope staff, NotificationService notifications, Clock clock,
                               @Value("${quest.materials.job.enabled:true}") boolean enabled) {
        this.schools = schools; this.flags = flags; this.calendar = calendar; this.deadlines = deadlines;
        this.teacherScope = teacherScope; this.submissions = submissions; this.reminders = reminders;
        this.staff = staff; this.notifications = notifications; this.clock = clock; this.enabled = enabled;
    }

    /** On Cloud Run an instance wakes up after a quiet spell; whatever fell due meanwhile is caught up at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void runAtStartup() { if (enabled) run(); }

    @Scheduled(fixedDelayString = "${quest.materials.job.interval-seconds:300}s",
               initialDelayString = "${quest.materials.job.interval-seconds:300}s")
    public void runPeriodically() { if (enabled) run(); }

    /** One pass over every school with teaching materials on. Answers how many reminders were sent. */
    public int run() {
        int sent = 0;
        for (var school : schools.findAll()) {
            if (!on(school.getId())) continue;
            try { sent += run(school.getId()); }
            catch (RuntimeException e) { log.warn("teaching materials: school {} skipped this run: {}", school.getId(), e.toString()); }
        }
        return sent;
    }

    /** One school's pass, on the school's own clock. */
    public int run(String schoolId) {
        Instant now = clock.instant();
        var week = calendar.of(schoolId);
        ZoneId zone = week.zone();
        // The week being prepared is the one after the one running now, and its deadline falls inside this week.
        LocalDate target = week.startOf(LocalDate.ofInstant(now, zone)).plusDays(7);
        var rule = deadlines.rule(schoolId);
        Instant dueAt = MaterialDeadlines.dueAt(rule, zone, target);
        Instant remindFrom = dueAt.minusSeconds(rule.remindHoursBefore() * 3600L);
        if (now.isBefore(remindFrom) || !now.isBefore(dueAt)) return 0;

        var expected = new HashMap<String, Integer>();
        for (var assignment : teacherScope.assignmentsIn(schoolId))
            expected.merge(assignment.getTeacherId(), 1, Integer::sum);
        if (expected.isEmpty()) return 0;
        var handedIn = new HashMap<String, Integer>();
        for (var row : submissions.findBySchoolIdAndWeekStart(schoolId, target))
            handedIn.merge(row.getTeacherId(), 1, Integer::sum);

        var employees = new HashSet<String>();
        for (var employee : staff.all(schoolId))
            if (StaffAttendanceDto.KIND_USER.equals(employee.kind())) employees.add(employee.id());

        int sent = 0;
        for (var teacher : new ArrayList<>(expected.keySet())) {
            if (!employees.contains(teacher)) continue;                      // disabled, or no longer of this school
            int missing = expected.get(teacher) - handedIn.getOrDefault(teacher, 0);
            if (missing <= 0) continue;
            if (!claim(schoolId, teacher, target, now)) continue;
            remind(schoolId, teacher, target, missing);
            sent++;
        }
        return sent;
    }

    // ---------------------------------------------------------------- one reminder

    private void remind(String schoolId, String teacherId, LocalDate week, int missing) {
        var link = new LinkedHashMap<String, String>();
        link.put(MaterialLink.TYPE, MaterialLink.REMINDER);
        link.put(MaterialLink.WEEK, week.toString());
        link.put(MaterialLink.MISSING, Integer.toString(missing));
        notifications.staffNotice(schoolId, teacherId, NotificationKind.MATERIAL_REMINDER,
                "Teaching material", missing + " of next week's teaching material is still missing.",
                "دفتر التحضير", "ما زال " + missing + " من تحضير الأسبوع القادم غير مسلّم",
                link(link), "material:reminder:" + week);
    }

    /** True when this call claimed the key and may send; false when an earlier run (or another instance) did. */
    private boolean claim(String schoolId, String teacherId, LocalDate week, Instant now) {
        var row = new MaterialReminderEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setTeacherId(teacherId);
        row.setWeekStart(week); row.setSentAt(now);
        return reminders.claim(row) == 1;
    }

    private boolean on(String schoolId) {
        try { return flags.isOn(schoolId, FlagKeys.TEACHING_MATERIALS); }
        catch (RuntimeException e) { return false; }
    }

    static String link(Map<String, String> params) {
        var parts = new ArrayList<String>();
        for (var e : params.entrySet()) parts.add(e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        return "/notifications?" + String.join("&", parts);
    }
}
