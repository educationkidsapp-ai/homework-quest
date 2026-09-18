package quest.server.teacher;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import quest.server.auth.Principals;
import quest.server.children.AttemptRepository;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.platform.SchoolCalendar;

/**
 * §6 screen 12 and `docs/teacher-flow.md` §7: "her lessons only, by class and date; the calendar view per class
 * shows gaps."
 *
 * <p>A gap is a <em>school day with no lesson</em>, so the calendar has to know which days a school teaches on.
 * Until N2.1 that was a Sunday–Thursday constant here; it is now {@link SchoolCalendar}, which reads the platform's
 * week and the school's override, and `schoolDay` travels with every day of the response so nothing on the client
 * hard-codes the same assumption either.
 *
 * <p>Three statements whatever the month holds: the class, its lessons in the window, and how many children have
 * played each of them.
 */
@Service
public class TeacherCalendarService {
    private final LessonRepository lessons; private final TeacherAccess access; private final AttemptRepository attempts;
    private final SchoolCalendar calendar;

    public TeacherCalendarService(LessonRepository lessons, TeacherAccess access, AttemptRepository attempts,
                                  SchoolCalendar calendar) {
        this.lessons = lessons; this.access = access; this.attempts = attempts; this.calendar = calendar;
    }

    /**
     * The class's month. `month` is `yyyy-MM` (N2.1) or a plain month number beside `year` (the P4.0 shape, kept
     * working until the dashboard has moved); both are absent for the current month.
     */
    public TeacherDto.ClassCalendar month(Principals.User caller, String classId, Integer year, String month) {
        var klass = access.readableClass(caller, classId);
        var week = calendar.of(klass.getSchoolId());
        YearMonth ym = yearMonth(year, month, LocalDate.now(week.zone()));

        var byDate = new HashMap<LocalDate, LessonEntity>();
        var rows = lessons.findByClassIdAndDateBetweenOrderByDateAsc(classId, ym.atDay(1), ym.atEndOfMonth());
        for (var lesson : rows) {
            // Two lessons on one day is legal (a class may run more than one); the calendar shows the published one,
            // and the newest otherwise, because that is the one the teacher is working on.
            var current = byDate.get(lesson.getDate());
            if (current == null || TeacherWeekService.better(lesson, current)) byDate.put(lesson.getDate(), lesson);
        }
        var players = new HashMap<String, Integer>();
        var lessonIds = rows.stream().map(LessonEntity::getId).toList();
        if (!lessonIds.isEmpty()) for (Object[] row : attempts.countPlayersByLessonIdIn(lessonIds)) players.put((String) row[0], ((Number) row[1]).intValue());

        var days = new ArrayList<TeacherDto.ClassCalendarDay>(ym.lengthOfMonth());
        LocalDate today = LocalDate.now(week.zone());
        int gaps = 0;
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            var lesson = byDate.get(date);
            boolean schoolDay = week.isSchoolDay(date);
            // A gap is only a gap once the day has arrived: a school day still in the future is simply not planned
            // yet, and colouring it red would make every new month look like a failure.
            boolean gap = schoolDay && lesson == null && !date.isAfter(today);
            if (gap) gaps++;
            days.add(new TeacherDto.ClassCalendarDay(date.toString(), lesson == null ? null : lesson.getId(),
                    lesson == null ? null : TeacherWeekService.status(lesson), lesson == null ? null : lesson.getType(),
                    lesson == null ? 0 : players.getOrDefault(lesson.getId(), 0), schoolDay, gap));
        }
        return new TeacherDto.ClassCalendar(klass.getId(), klass.getCurriculum(), klass.getGrade(), klass.getSubject(),
                ym.getYear(), ym.getMonthValue(), List.copyOf(days), gaps);
    }

    /** `2026-03`, or a month number with an optional year, or neither. */
    private static YearMonth yearMonth(Integer year, String month, LocalDate today) {
        if (month != null && month.contains("-")) {
            try { return YearMonth.parse(month.trim()); }
            catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("month must be yyyy-MM"); }
        }
        int y = year == null ? today.getYear() : year;
        int m = month == null || month.isBlank() ? today.getMonthValue() : number(month);
        if (y < 2000 || y > 2100) throw ApiException.badRequest("year must be between 2000 and 2100");
        if (m < 1 || m > 12) throw ApiException.badRequest("month must be between 1 and 12");
        return YearMonth.of(y, m);
    }

    private static int number(String month) {
        try { return Integer.parseInt(month.trim()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("month must be yyyy-MM or a month number"); }
    }
}
