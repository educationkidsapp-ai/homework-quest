package quest.server.teacher;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;

/**
 * §6 screen 12: "her lessons only, by class and date; the calendar view per class shows gaps."
 *
 * <p>A gap is a <em>school day with no lesson</em>. A weekend with no lesson is not a gap, so the calendar needs to
 * know which days are school days — see {@link #SCHOOL_WEEK}.
 *
 * <p>Two statements whatever the month holds: the class, and its lessons in the window.
 */
@Service
public class TeacherCalendarService {
    /**
     * <strong>The school week is assumed to be Sunday–Thursday</strong> — the Gulf week the QA schools run, and the
     * one §6's "gaps" was written against.
     *
     * <p>It is deliberately not configurable yet: no school on the platform runs a different week, and a column
     * nobody sets is a column that rots. When the first Monday–Friday school is onboarded, `schools` gains a
     * `school_week` (a 7-character mask, or a JSON list of day names) in an additive migration, {@link #isSchoolDay}
     * reads it, and `ClassCalendarDay.schoolDay` starts answering something other than this constant. Nothing on the
     * client may hard-code the same assumption: the flag travels with every day of the response for exactly that
     * reason.
     */
    static final Set<DayOfWeek> SCHOOL_WEEK = Set.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);

    private final LessonRepository lessons; private final TeacherAccess access;

    public TeacherCalendarService(LessonRepository lessons, TeacherAccess access) {
        this.lessons = lessons; this.access = access;
    }

    public TeacherDto.ClassCalendar month(Principals.User caller, String classId, Integer year, Integer month) {
        var klass = access.readableClass(caller, classId);
        YearMonth ym = yearMonth(year, month);

        var byDate = new HashMap<LocalDate, LessonEntity>();
        for (var lesson : lessons.findByClassIdAndDateBetweenOrderByDateAsc(classId, ym.atDay(1), ym.atEndOfMonth())) {
            // Two lessons on one day is legal (a class may run more than one); the calendar shows the published one,
            // and the newest otherwise, because that is the one the teacher is working on.
            var current = byDate.get(lesson.getDate());
            if (current == null || better(lesson, current)) byDate.put(lesson.getDate(), lesson);
        }

        var days = new ArrayList<TeacherDto.ClassCalendarDay>(ym.lengthOfMonth());
        LocalDate today = LocalDate.now();
        int gaps = 0;
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            var lesson = byDate.get(date);
            boolean schoolDay = isSchoolDay(date);
            // A gap is only a gap once the day has arrived: a school day still in the future is simply not planned
            // yet, and colouring it red would make every new month look like a failure.
            boolean gap = schoolDay && lesson == null && !date.isAfter(today);
            if (gap) gaps++;
            days.add(new TeacherDto.ClassCalendarDay(date.toString(),
                    lesson == null ? null : lesson.getId(), lesson == null ? null : lesson.getStatus(), schoolDay, gap));
        }
        return new TeacherDto.ClassCalendar(klass.getId(), klass.getCurriculum(), klass.getGrade(), klass.getSubject(),
                ym.getYear(), ym.getMonthValue(), List.copyOf(days), gaps);
    }

    /** See {@link #SCHOOL_WEEK}: Sunday–Thursday until a school says otherwise. */
    static boolean isSchoolDay(LocalDate date) { return SCHOOL_WEEK.contains(date.getDayOfWeek()); }

    private static boolean better(LessonEntity candidate, LessonEntity current) {
        boolean candidatePublished = "published".equals(candidate.getStatus());
        boolean currentPublished = "published".equals(current.getStatus());
        if (candidatePublished != currentPublished) return candidatePublished;
        return candidate.getCreatedAt() != null && current.getCreatedAt() != null
                && candidate.getCreatedAt().isAfter(current.getCreatedAt());
    }

    private static YearMonth yearMonth(Integer year, Integer month) {
        var now = YearMonth.now();
        int y = year == null ? now.getYear() : year;
        int m = month == null ? now.getMonthValue() : month;
        if (y < 2000 || y > 2100) throw ApiException.badRequest("year must be between 2000 and 2100");
        if (m < 1 || m > 12) throw ApiException.badRequest("month must be between 1 and 12");
        return YearMonth.of(y, m);
    }
}
