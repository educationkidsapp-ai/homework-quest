package quest.server.evaluations;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quest.api.dashboard.EvaluationLink;
import quest.api.dto.NotificationKind;
import quest.server.children.ChildRepository;
import quest.server.evaluations.Entities.EvaluationReminderEntity;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.staffattendance.StaffAttendanceDto;
import quest.server.staffattendance.StaffBoardScope;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 33: every five minutes, for each school with `childEvaluations` on, reminds the teachers whose evaluations
 * for an open round are still missing — from `remind_hours_before` before the round's deadline until the deadline
 * itself, and never after it. A released round reminds nobody: it is over.
 *
 * <p><strong>Once per round, whatever happens.</strong> Each reminder is claimed in `evaluation_reminders` under
 * (school, round, teacher) with ON CONFLICT DO NOTHING, and is sent only by the run whose insert wrote the row. A
 * rerun sends nothing new, and neither does a second Cloud Run instance running the job at the same moment —
 * feature 31's rule exactly ({@link quest.server.materials.MaterialReminderJob}).
 *
 * <p><strong>Scope.</strong> The job has no caller and so no tenant ({@link quest.server.tenancy.TenantContext} is
 * null on the scheduler's thread): every query names the school, as every repository of this package requires.
 */
@Component
public class EvaluationReminderJob {
    private static final Logger log = LoggerFactory.getLogger(EvaluationReminderJob.class);

    private final SchoolRepository schools; private final FeatureFlags flags; private final EvaluationRounds rounds;
    private final TeacherScope teacherScope; private final ChildRepository children;
    private final ChildEvaluationRepository evaluations; private final EvaluationReminderRepository reminders;
    private final StaffBoardScope staff; private final NotificationService notifications;
    private final Clock clock; private final boolean enabled;

    public EvaluationReminderJob(SchoolRepository schools, FeatureFlags flags, EvaluationRounds rounds,
                                 TeacherScope teacherScope, ChildRepository children,
                                 ChildEvaluationRepository evaluations, EvaluationReminderRepository reminders,
                                 StaffBoardScope staff, NotificationService notifications, Clock clock,
                                 @Value("${quest.evaluations.job.enabled:true}") boolean enabled) {
        this.schools = schools; this.flags = flags; this.rounds = rounds; this.teacherScope = teacherScope;
        this.children = children; this.evaluations = evaluations; this.reminders = reminders; this.staff = staff;
        this.notifications = notifications; this.clock = clock; this.enabled = enabled;
    }

    /** On Cloud Run an instance wakes up after a quiet spell; whatever fell due meanwhile is caught up at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void runAtStartup() { if (enabled) run(); }

    @Scheduled(fixedDelayString = "${quest.evaluations.job.interval-seconds:300}s",
               initialDelayString = "${quest.evaluations.job.interval-seconds:300}s")
    public void runPeriodically() { if (enabled) run(); }

    /** One pass over every school with child evaluations on. Answers how many reminders were sent. */
    public int run() {
        int sent = 0;
        for (var school : schools.findAll()) {
            if (!on(school.getId())) continue;
            try { sent += run(school.getId()); }
            catch (RuntimeException e) { log.warn("child evaluations: school {} skipped this run: {}", school.getId(), e.toString()); }
        }
        return sent;
    }

    /** One school's pass: every round whose reminder window is open right now. */
    public int run(String schoolId) {
        Instant now = clock.instant();
        int sent = 0;
        for (var round : rounds.all(schoolId)) {
            if (round.isReleased()) continue;
            Instant remindFrom = round.getDueAt().minusSeconds(round.getRemindHoursBefore() * 3600L);
            if (now.isBefore(remindFrom) || !now.isBefore(round.getDueAt())) continue;
            sent += run(schoolId, round.getId(), now);
        }
        return sent;
    }

    private int run(String schoolId, String roundId, Instant now) {
        // What is expected: one evaluation per (class, subject) she teaches times the children placed in that class.
        var expected = new HashMap<String, Integer>();
        var roster = new HashMap<String, Integer>();
        for (var assignment : teacherScope.assignmentsIn(schoolId)) {
            int kids = roster.computeIfAbsent(assignment.getClassId(),
                    id -> children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(id).size());
            if (kids > 0) expected.merge(assignment.getTeacherId(), kids, Integer::sum);
        }
        if (expected.isEmpty()) return 0;
        var written = new HashMap<String, Integer>();
        for (var row : evaluations.findBySchoolIdAndRoundId(schoolId, roundId))
            if (!EvaluationDto.DRAFT.equals(row.getState())) written.merge(row.getTeacherId(), 1, Integer::sum);

        var employees = new HashSet<String>();
        for (var employee : staff.all(schoolId))
            if (StaffAttendanceDto.KIND_USER.equals(employee.kind())) employees.add(employee.id());

        int sent = 0;
        for (var teacher : new ArrayList<>(expected.keySet())) {
            if (!employees.contains(teacher)) continue;                      // disabled, or no longer of this school
            int missing = expected.get(teacher) - written.getOrDefault(teacher, 0);
            if (missing <= 0) continue;
            if (!claim(schoolId, roundId, teacher, now)) continue;
            remind(schoolId, roundId, teacher, missing);
            sent++;
        }
        return sent;
    }

    // ---------------------------------------------------------------- one reminder

    private void remind(String schoolId, String roundId, String teacherId, int missing) {
        notifications.staffNotice(schoolId, teacherId, NotificationKind.EVALUATION_REMINDER,
                "Child evaluations", missing + " of your children's evaluations are still missing.",
                "تقييم الطلاب", "ما زال " + missing + " من تقييمات طلابك غير مكتمل",
                ChildEvaluationService.link(roundId, EvaluationLink.REMINDER, missing), "evaluation:reminder:" + roundId);
    }

    /** True when this call claimed the key and may send; false when an earlier run (or another instance) did. */
    private boolean claim(String schoolId, String roundId, String teacherId, Instant now) {
        var row = new EvaluationReminderEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setRoundId(roundId);
        row.setTeacherId(teacherId); row.setSentAt(now);
        return reminders.claim(row) == 1;
    }

    private boolean on(String schoolId) {
        try { return flags.isOn(schoolId, FlagKeys.CHILD_EVALUATIONS); }
        catch (RuntimeException e) { return false; }
    }
}
