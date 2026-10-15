package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.staff.CreateRemoteStudyDay;
import quest.api.staff.RemoteStudyDay;
import quest.api.staff.RemoteStudyDayList;
import quest.server.auth.Principals;
import quest.server.broadcasts.BroadcastService;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.RemoteStudyDayEntity;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * Feature 23: the Admin declares a <strong>remote study day</strong> — the whole school, one academy, chosen grades,
 * or one academy's chosen grades study from home. It replaces feature 18's school-wide remote day, whose rows V46
 * copied into `remote_study_days`; feature 18's per-employee request is untouched and needs no flag.
 *
 * <p><strong>Two audiences, two scopes.</strong> Staff are in scope by academy alone and
 * {@link RemoteDayService#appliesTo} lets them check in from anywhere; children and their parents are in scope by the
 * academy <em>and</em> the grade, which is what {@link #covers} answers for the parent's Today and the teacher's
 * class attendance.
 *
 * <p><strong>Creating one tells both.</strong> Staff get feature 18's notice, in its own words and with its own
 * dedupe ({@link RemoteDayService#announce}); the parents of every class in scope get a bilingual announcement
 * through the ordinary broadcast fan-out, expiring at the end of that day in the school's zone, and the row keeps its
 * id. Deleting the day expires that announcement and sends nothing new — a parent is never told twice, and never
 * told that something was cancelled by a push she cannot act on.
 */
@Service
public class RemoteStudyDayService {
    private static final int MAX_REASON = 200;

    private final RemoteStudyDayRepository days; private final RemoteDayService remoteDays;
    private final BroadcastService broadcasts; private final SchoolRepository schools;
    private final ClassRepository classes; private final SchoolCalendar calendar;
    private final FeatureFlags flags; private final ManagerScope managers; private final TenantContext tenant;
    private final Json json; private final Clock clock;

    public RemoteStudyDayService(RemoteStudyDayRepository days, RemoteDayService remoteDays, BroadcastService broadcasts,
                                 SchoolRepository schools, ClassRepository classes, SchoolCalendar calendar,
                                 FeatureFlags flags, ManagerScope managers, TenantContext tenant, Json json, Clock clock) {
        this.days = days; this.remoteDays = remoteDays; this.broadcasts = broadcasts; this.schools = schools;
        this.classes = classes; this.calendar = calendar; this.flags = flags; this.managers = managers;
        this.tenant = tenant; this.json = json; this.clock = clock;
    }

    // ---------------------------------------------------------------- the Admin's list

    /** Today's and the coming remote study days, in date order: a past one is history, not something to manage. */
    @Transactional(readOnly = true)
    public RemoteStudyDayList list() {
        String schoolId = tenant.writeSchoolId();
        var out = new ArrayList<RemoteStudyDay>();
        for (var d : days.findBySchoolIdAndDayGreaterThanEqualOrderByDayAsc(schoolId, calendar.today(schoolId)))
            out.add(view(d));
        return new RemoteStudyDayList(out);
    }

    @Transactional
    public RemoteStudyDay create(Principals.User caller, CreateRemoteStudyDay request) {
        String schoolId = tenant.writeSchoolId();
        LocalDate date = RemoteDayService.date(request.getDate());
        LocalDate today = calendar.today(schoolId);
        if (date.isBefore(today)) throw ApiException.badRequest("A remote study day is today or later.");
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON) throw ApiException.badRequest("reason is 1–" + MAX_REASON + " characters.");
        var school = schools.findById(schoolId).orElseThrow(() -> ApiException.notFound("School"));
        String academy = academy(school, request.getAcademy());
        var grades = grades(school, request.getGrades());

        var row = new RemoteStudyDayEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setDay(date);
        row.setAcademy(academy); row.setGradesJson(grades.isEmpty() ? null : json.write(grades));
        row.setReason(reason); row.setCreatedBy(caller.userId()); row.setCreatedAt(Instant.now(clock));
        days.save(row);

        tellParents(schoolId, row, caller.userId());
        // Too late for the evening notice, so staff hear about it now — in feature 18's words, with its own dedupe.
        if (!date.isAfter(today.plusDays(1))) remoteDays.announce(schoolId, row, today, Instant.now(clock));
        return view(row);
    }

    @Transactional
    public void delete(String id) {
        String schoolId = tenant.writeSchoolId();
        var row = days.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("Remote study day"));
        if (row.getBroadcastId() != null) broadcasts.expireNow(row.getBroadcastId(), Instant.now(clock));
        days.delete(row);
    }

    // ---------------------------------------------------------------- what the children's screens ask

    /** Whether a remote study day covers one class — its academy and its grade — on {@code date}. */
    @Transactional(readOnly = true)
    public boolean covers(String schoolId, String curriculum, int grade, LocalDate date) {
        if (!flags.isOn(schoolId, FlagKeys.REMOTE_STUDY_DAYS)) return false;
        return days.findBySchoolIdAndDay(schoolId, date).stream().anyMatch(d -> inChildScope(d, curriculum, grade));
    }

    /**
     * The days of a window that cover one class, in one statement — what a month of
     * {@link quest.server.attendance.AttendanceService#classAttendanceWindow} needs instead of a query per day.
     */
    @Transactional(readOnly = true)
    public Set<LocalDate> coveredDates(String schoolId, String curriculum, int grade, LocalDate from, LocalDate to) {
        if (!flags.isOn(schoolId, FlagKeys.REMOTE_STUDY_DAYS)) return Set.of();
        var out = new LinkedHashSet<LocalDate>();
        for (var d : days.findBySchoolIdAndDayBetweenOrderByDayAsc(schoolId, from, to))
            if (inChildScope(d, curriculum, grade)) out.add(d.getDay());
        return out;
    }

    /** Feature 25: today's remote study day as one leader sees it, and why it was declared. */
    public record LeaderDay(boolean on, String reason) {
        static final LeaderDay NONE = new LeaderDay(false, null);
    }

    /**
     * Feature 25: whether a remote study day covers any academy this leader reaches on {@code date}.
     *
     * <p><strong>A leader is staff, so the scope is the academy alone</strong> — the grades narrow children and
     * parents, never the people who run the place ({@link RemoteDayService#appliesTo}'s rule, and this class's own
     * "two audiences, two scopes"). A day declared for the whole school ({@code academy} null) is everybody's, and a
     * manager's own departments are {@link ManagerScope#departments}; the Admin reaches all of them. The reason comes
     * back with it, because "the whole school studies from home today" is the one line of a brief that would be
     * useless without its why.
     *
     * <p>Flag-gated here rather than at the caller, as {@link #covers} is: with `remoteStudyDays` off a remote study
     * day does not exist at all.
     */
    @Transactional(readOnly = true)
    public LeaderDay forLeader(String schoolId, Principals.User caller, LocalDate date) {
        if (!flags.isOn(schoolId, FlagKeys.REMOTE_STUDY_DAYS)) return LeaderDay.NONE;
        Set<String> mine = managers.isManager(caller) ? Set.copyOf(managers.departments(caller)) : null;
        for (var day : days.findBySchoolIdAndDay(schoolId, date))
            if (mine == null || day.getAcademy() == null || mine.contains(day.getAcademy()))
                return new LeaderDay(true, day.getReason());
        return LeaderDay.NONE;
    }

    private boolean inChildScope(RemoteStudyDayEntity day, String curriculum, int grade) {
        if (day.getAcademy() != null && !day.getAcademy().equals(ManagerScope.normalise(curriculum))) return false;
        var only = stored(day.getGradesJson());
        return only.isEmpty() || only.contains(grade);
    }

    // ---------------------------------------------------------------- the parents' announcement

    /**
     * The parents of every section in scope, through the broadcast every parent's feed already reads: an announcement
     * of kind {@code announcement}, bilingual, expiring at the end of that day in the school's zone. A day that
     * reaches no section of the school posts nothing and keeps a null `broadcast_id` — there is nobody to tell.
     */
    private void tellParents(String schoolId, RemoteStudyDayEntity row, String authorId) {
        var sections = sections(schoolId, row);
        if (sections.isEmpty()) return;
        ZoneId zone = calendar.of(schoolId).zone();
        Instant endOfDay = row.getDay().plusDays(1).atStartOfDay(zone).toInstant();
        String bodyEn = "Remote study day on " + row.getDay() + ": " + row.getReason()
                + ". Children in scope study from home that day.";
        String bodyAr = "يوم دراسة عن بعد في " + row.getDay() + ": " + row.getReason() + ". يدرس الأبناء من المنزل في ذلك اليوم.";
        row.setBroadcastId(broadcasts.announceToParents(schoolId, authorId, sections,
                "Remote study day · يوم دراسة عن بعد", bodyEn, bodyAr, endOfDay));
        days.save(row);
    }

    /** The school's named sections this day covers: its academy and its grades, as the children's scope reads them. */
    private List<ClassEntity> sections(String schoolId, RemoteStudyDayEntity row) {
        return classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId).stream()
                .filter(k -> inChildScope(row, k.getCurriculum(), k.getGrade())).toList();
    }

    // ---------------------------------------------------------------- what a request may carry

    /** Null or blank is every academy; anything else must be one of the school's curricula, compared lower-cased. */
    private String academy(quest.server.tenancy.Entities.SchoolEntity school, String value) {
        if (value == null || value.isBlank()) return null;
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        List<String> curricula = school.getCurriculumOptionsJson() == null ? List.of() : json.strings(school.getCurriculumOptionsJson());
        if (curricula.stream().noneMatch(c -> c != null && c.trim().toLowerCase(Locale.ROOT).equals(wanted)))
            throw ApiException.badRequest("academy is one of the school's curricula, or empty for all of them.");
        return wanted;
    }

    /** Empty is every grade; anything else must be grades the school really has, de-duplicated and in order. */
    private List<Integer> grades(quest.server.tenancy.Entities.SchoolEntity school, List<Integer> wanted) {
        if (wanted == null || wanted.isEmpty()) return List.of();
        var real = stored(school.getGradeOptionsJson());
        var out = new java.util.TreeSet<Integer>();
        for (Integer grade : wanted) {
            if (grade == null || !real.contains(grade))
                throw ApiException.badRequest("grades are grades the school teaches, or empty for all of them.");
            out.add(grade);
        }
        return List.copyOf(out);
    }

    /** A stored JSON array of grades; an empty list for null, blank or anything that is not one. */
    private Set<Integer> stored(String value) {
        if (value == null || value.isBlank()) return Set.of();
        var out = new LinkedHashSet<Integer>();
        var tree = json.tree(value);
        if (tree.isArray()) tree.forEach(node -> { if (node.isNumber()) out.add(node.asInt()); });
        return out;
    }

    private RemoteStudyDay view(RemoteStudyDayEntity d) {
        return new RemoteStudyDay(d.getId(), d.getDay().toString(), d.getAcademy(), List.copyOf(stored(d.getGradesJson())), d.getReason());
    }
}
