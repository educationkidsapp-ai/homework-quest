package quest.server.teacher;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;

/**
 * §5's teacher profile and §6 screens 12, 13 and 15 — the parts of a teacher's dashboard that are not a feature.
 *
 * <p><strong>Carries no {@link FeatureFlag}</strong>, and is listed as core in `FeatureFlagCoverageTest` beside
 * `HomeController` and `DashboardDataController`, for the same kind of reason those are: a teacher's profile is what
 * decides which lessons she may publish at all ({@link quest.server.tenancy.TenantGuard#lessonCreator} reads it), the
 * chooser options are the only way to submit a lesson the server will accept, and her lessons and her students are
 * the job rather than an addition to it. A flag over them would leave a TEACHER account signed in with nothing it
 * can do. The two things here that *are* features — questions to students and announcements — live in
 * {@link TeacherQuestionController} and {@link AnnouncementController}, each behind its own flag.
 *
 * <p><strong>Scope.</strong> `/teacher/**` names no school: it serves the caller's own, read from her token, so
 * there is nowhere to ask for another one — the same shape `/school/**` uses for Managerial. A class or a child in
 * the path is resolved through {@link TeacherAccess} and {@link quest.server.children.ChildService#scoped}, so
 * another school's is a 404 and another teacher's class is a 403.
 */
@RestController
@Tag(name = "Teacher", description = "Teacher profile, lesson chooser options, calendar and students")
public class TeacherController {
    private final TeacherProfileService profiles; private final TeacherCalendarService calendar;
    private final TeacherStudentService students; private final TeacherWeekService week;
    private final TeacherMessageService messages;

    public TeacherController(TeacherProfileService profiles, TeacherCalendarService calendar,
                             TeacherStudentService students, TeacherWeekService week,
                             TeacherMessageService messages) {
        this.profiles = profiles; this.calendar = calendar; this.students = students; this.week = week;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- a word to the coordinator (U1 item 2)

    /**
     * `POST /teacher/messages/coordinator`: her message to the people who handle the school's messages.
     *
     * Core rather than a feature, like the rest of this controller: a teacher who cannot reach her own school's
     * office is a teacher with no way to raise anything at all. See {@link TeacherMessageService} for why this
     * exists instead of reusing questions, announcements or chat.
     */
    @PostMapping(value = "/teacher/messages/coordinator", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.message.coordinator')")
    public TeacherDto.CoordinatorMessageResult messageCoordinator(@AuthenticationPrincipal Principals.User caller,
                                                                  @RequestBody @Valid TeacherDto.CoordinatorMessageRequest body) {
        return messages.toCoordinator(TeacherAccess.require(caller), body.body().strip());
    }

    // ---------------------------------------------------------------- profile (§5)

    @GetMapping(value = "/teacher/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.profile')")
    public TeacherDto.TeacherProfile myTeacherProfile(@AuthenticationPrincipal Principals.User caller) {
        return profiles.mine(TeacherAccess.require(caller));
    }

    @PutMapping(value = "/teacher/profile", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.profile')")
    public TeacherDto.TeacherProfile saveMyTeacherProfile(@AuthenticationPrincipal Principals.User caller,
                                                          @RequestBody @Valid TeacherDto.UpdateTeacherProfileRequest body) {
        return profiles.saveMine(TeacherAccess.require(caller), body);
    }

    /** §6 screen 13: what the New lesson chooser may offer — her curriculum, grades, subjects and classes. */
    @GetMapping(value = "/teacher/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.options')")
    public TeacherDto.TeacherOptions teacherOptions(@AuthenticationPrincipal Principals.User caller) {
        return profiles.options(TeacherAccess.require(caller));
    }

    /** ADMIN only (§6 screen 6): read and write any teacher's profile from the Users screen, in any school. */
    @GetMapping(value = "/admin/users/{id}/teacher-profile", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.profile.manage')")
    public TeacherDto.TeacherProfile teacherProfileOf(@PathVariable String id) { return profiles.of(id); }

    @PutMapping(value = "/admin/users/{id}/teacher-profile", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.profile.manage')")
    public TeacherDto.TeacherProfile saveTeacherProfileOf(@PathVariable String id,
                                                          @RequestBody @Valid TeacherDto.UpdateTeacherProfileRequest body) {
        return profiles.save(id, body);
    }

    // ---------------------------------------------------------------- my lessons (§6 screen 12)

    /**
     * `docs/teacher-flow.md` §4: every assignment she holds against one school week, in one response. `start` is any
     * day of the week wanted and is snapped back to that week's first teaching day, so the dashboard's prev/next may
     * move by seven days without knowing where a week begins; absent means this week, in the school's own timezone.
     */
    @GetMapping(value = "/teacher/week", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.week')")
    public TeacherDto.TeacherWeek myWeek(@AuthenticationPrincipal Principals.User caller,
                                         @RequestParam(required = false) String start) {
        return week.week(TeacherAccess.require(caller), start);
    }

    /** §7 My classes: a card per assignment — today's lesson, the class size, and how many have played it. */
    @GetMapping(value = "/teacher/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.week')")
    public List<TeacherDto.TeacherClassCard> myClasses(@AuthenticationPrincipal Principals.User caller) {
        return week.classes(TeacherAccess.require(caller));
    }

    /**
     * The class's month with the gaps flagged. `month` is `yyyy-MM` (N2.1); `?year=&month=<number>` is the P4.0
     * shape and keeps working until the dashboard has moved. Both absent is the current month.
     */
    @GetMapping(value = "/teacher/classes/{classId}/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('calendar.read')")
    public TeacherDto.ClassCalendar classCalendar(@AuthenticationPrincipal Principals.User caller,
                                                  @PathVariable String classId,
                                                  @RequestParam(required = false) Integer year,
                                                  @RequestParam(required = false) String month) {
        return calendar.month(TeacherAccess.require(caller), classId, year, month);
    }

    // ---------------------------------------------------------------- my students (§6 screen 15)

    @GetMapping(value = "/teacher/classes/{classId}/students", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('student.read')")
    public List<TeacherDto.ClassStudent> classStudents(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId) {
        return students.students(TeacherAccess.require(caller), classId);
    }

    /** What one child played in the window, with the retells and drawings she saved (`/media/child/{id}` URLs). */
    @GetMapping(value = "/teacher/students/{childId}/timeline", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('student.read')")
    public TeacherDto.StudentTimeline studentTimeline(@AuthenticationPrincipal Principals.User caller,
                                                      @PathVariable String childId,
                                                      @RequestParam(required = false) String from,
                                                      @RequestParam(required = false) String to) {
        return students.timeline(TeacherAccess.require(caller), childId, from, to);
    }
}
