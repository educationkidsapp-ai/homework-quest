package quest.server.floors;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import quest.server.attendance.AttendanceService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.classes.SectionService;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.floors.Entities.FloorEntity;
import quest.server.grading.GradingDto;
import quest.server.grading.GradingService;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.FloorScope;
import quest.server.tenancy.TenantContext;

/**
 * The six reads behind `/floor/**` (feature 20): her floors and their three numbers, today's staff and children's
 * attendance, her teachers, her classes, her children and one child's page.
 *
 * <p><strong>Nothing here decides what she reaches.</strong> {@link FloorScope} does, once, and everything below
 * starts from {@link FloorScope#reach} or one of its `require…` checks. Nothing takes a school or a floor from the
 * request: `classId` and `childId` are the only parameters that name a row, and each is resolved through
 * {@link FloorScope#requireSection} / {@link FloorScope#requireChild} before it is used.
 *
 * <p><strong>Her numbers are the numbers that already exist.</strong> The staff half of Home is feature 17's own
 * board ({@link quest.server.staffattendance.AttendanceBoardService#board}), narrowed by `BoardScope`'s new
 * FLOOR_SUPERVISOR case; the children's half is the teacher's own register
 * ({@link AttendanceService#classAttendanceWindow}) for one day; the child page is §7's
 * {@link GradingService#child}. There is no second attendance rate and no second child report to drift out of step.
 *
 * <p><strong>Bulk, not per row.</strong> The reach, the class sizes and the teachers' names are read once per
 * request, as {@code CoordinatorService} reads them, so the statement count does not move with the number of classes.
 * Today's register is the one read that is per class, because a day of one section is what
 * {@code classAttendanceWindow} answers — the same statement the teacher's own screen makes.
 *
 * <p><strong>Today is the school's today</strong>, read in the school's own zone through {@link SchoolCalendar}, so a
 * Gulf school's supervisor opens on her own date rather than the server's.
 */
@Service
public class FloorService {
    /** `GET /floor/children?q=`: the widest page the screen draws, the number MA1's directory uses. */
    static final int MAX_CHILDREN = 200;

    private final FloorScope scope; private final UserRepository users; private final ChildRepository children;
    private final AttendanceService attendance; private final GradingService grading;
    private final quest.server.staffattendance.AttendanceBoardService board; private final SchoolCalendar calendar;
    private final FeatureFlags flags; private final TenantContext tenant;

    public FloorService(FloorScope scope, UserRepository users, ChildRepository children, AttendanceService attendance,
                        GradingService grading, quest.server.staffattendance.AttendanceBoardService board,
                        SchoolCalendar calendar, FeatureFlags flags, TenantContext tenant) {
        this.scope = scope; this.users = users; this.children = children; this.attendance = attendance;
        this.grading = grading; this.board = board; this.calendar = calendar; this.flags = flags; this.tenant = tenant;
    }

    // ---------------------------------------------------------------- GET /floor/me

    /** Her floors and their three numbers, so the Home screen costs one request. */
    public FloorDto.FloorMe me(Principals.User caller) {
        var reach = scope.reach(caller);
        var teachers = new LinkedHashSet<String>();
        for (var a : reach.sections().assignments()) teachers.add(a.getTeacherId());
        int pupils = 0;
        for (var count : childCounts(reach.sections().sectionIds()).values()) pupils += count;
        var displayName = users.findById(caller.userId()).map(SectionService::displayName).orElse(caller.email());
        return new FloorDto.FloorMe(caller.userId(), caller.email(), displayName,
                reach.floors().stream().map(FloorService::ref).toList(),
                reach.sections().sections().size(), teachers.size(), pupils);
    }

    // ---------------------------------------------------------------- GET /floor/home

    /**
     * Today, in the school's zone: the staff attendance board for the people she leads, and the children's register
     * of every class on her floors.
     *
     * <p>{@code board} is null when the school has `staffAttendance` off — the feature her half of the screen is
     * made of does not exist there, and 404ing the whole of Home for it would take her children's attendance away
     * too. The children's half never depends on a flag: taking register is not an optional feature
     * (`AttendanceController` names no flag either).
     */
    public FloorDto.FloorHome home(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var reach = scope.reach(caller);
        LocalDate today = calendar.today(schoolId);
        var staff = flags.isOn(schoolId, FlagKeys.STAFF_ATTENDANCE) ? board.board(caller) : null;
        var floors = byId(reach.floors());
        var onFloor = scope.floorOfSection(reach.floors());
        var sizes = childCounts(reach.sections().sectionIds());
        var rows = new ArrayList<FloorDto.FloorClassAttendance>(reach.sections().sections().size());
        for (var section : reach.sections().sections()) {
            var day = attendance.classAttendanceWindow(section, today, today).getFirst();
            var floor = floors.get(onFloor.get(section.getId()));
            rows.add(new FloorDto.FloorClassAttendance(section.getId(), section.getName(), section.getCurriculum(),
                    section.getGrade(), floor == null ? null : floor.getId(), floor == null ? null : floor.getName(),
                    sizes.getOrDefault(section.getId(), 0), day.presentCount(), day.absentCount(), day.lateCount(),
                    day.excusedCount(), notMarked(day)));
        }
        return new FloorDto.FloorHome(today.toString(), staff, List.copyOf(rows));
    }

    // ---------------------------------------------------------------- GET /floor/teachers and /floor/classes

    /** Every teacher holding an assignment on one of her floors, with the sections and subjects that put her there. */
    public List<FloorDto.FloorTeacher> teachers(Principals.User caller) {
        var reach = scope.reach(caller);
        var cards = cards(reach);
        var sections = new LinkedHashMap<String, List<FloorDto.FloorClass>>();
        var subjects = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var a : reach.sections().assignments()) {
            var card = cards.get(a.getClassId());
            if (card == null) continue;
            sections.computeIfAbsent(a.getTeacherId(), k -> new ArrayList<>()).add(card);
            subjects.computeIfAbsent(a.getTeacherId(), k -> new LinkedHashSet<>()).add(a.getSubject());
        }
        if (sections.isEmpty()) return List.of();
        var rows = new ArrayList<FloorDto.FloorTeacher>(sections.size());
        for (var user : sortedByName(sections.keySet()))
            rows.add(new FloorDto.FloorTeacher(user.getId(), user.getEmail(), SectionService.displayName(user),
                    user.getPhotoUrl(), user.getPhone(), List.copyOf(subjects.getOrDefault(user.getId(), new LinkedHashSet<>())),
                    List.copyOf(sections.getOrDefault(user.getId(), List.of()))));
        return List.copyOf(rows);
    }

    /** One card per class on her floors — grade, track, the floor it is on and how many children sit in it. */
    public List<FloorDto.FloorClass> classes(Principals.User caller) {
        return List.copyOf(cards(scope.reach(caller)).values());
    }

    // ---------------------------------------------------------------- GET /floor/children

    /**
     * The children on her floors, by name or by either address the school holds — the directory statement MA1's
     * Children page already makes ({@link ChildRepository#findDirectory}), with her sections as the scope.
     */
    public List<FloorDto.FloorChild> children(Principals.User caller, String q) {
        var reach = scope.reach(caller);
        var ids = reach.sections().sectionIds();
        if (ids.isEmpty()) return List.of();
        var floors = byId(reach.floors());
        var onFloor = scope.floorOfSection(reach.floors());
        var rows = children.findDirectory(ids, quest.server.management.PeopleDirectoryService.pattern(q),
                org.springframework.data.domain.PageRequest.of(0, MAX_CHILDREN,
                        org.springframework.data.domain.Sort.by("name").ascending().and(org.springframework.data.domain.Sort.by("id").ascending())));
        var out = new ArrayList<FloorDto.FloorChild>(rows.size());
        for (ChildEntity child : rows) {
            var section = reach.sections().byId().get(child.getClassId());
            var floor = floors.get(onFloor.get(child.getClassId()));
            out.add(new FloorDto.FloorChild(child.getId(), child.getName(), child.getAvatarColor(), child.getClassId(),
                    section == null ? null : section.getName(), floor == null ? null : floor.getId(),
                    floor == null ? null : floor.getName()));
        }
        return List.copyOf(out);
    }

    /**
     * `GET /floor/children/{id}` — the read-only child page: §7's own {@link GradingDto.ChildReport}, every subject
     * of her section, because a floor supervisor looks across the classes of a floor rather than down one subject.
     * {@link GradingService#child} narrows a TEACHER and nobody else, which is safe only because the id has already
     * been resolved through {@link FloorScope#requireChild} — 404 for another school's, 403 for another floor's.
     */
    public GradingDto.ChildReport child(Principals.User caller, String childId) {
        var child = scope.requireChild(caller, childId);
        return grading.child(caller, child.getId(), CoordinatorScope.Subjects.ALL);
    }

    // ---------------------------------------------------------------- GET /floor/chat/peers

    /**
     * Whom `POST /floor/chat/threads` will accept besides a parent of her floor (named by `childId`) and a teacher on
     * it (`GET /floor/teachers`): the school's managers and the platform Admins. Her own floors are resolved first, so
     * the route is a floor supervisor's and `FloorScopeArchitectureTest` sees the check the write already makes.
     */
    public FloorDto.FloorChatPeers peers(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        scope.floorsOf(caller);
        var managers = users.findBySchoolIdAndRole(schoolId, "MANAGERIAL").stream()
                .filter(u -> !"disabled".equals(u.getStatus()))
                .sorted(Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> person(u, "MANAGERIAL")).toList();
        var admins = new ArrayList<FloorDto.FloorPerson>();
        for (String id : users.findActiveAdminIds())
            users.findById(id).ifPresent(u -> admins.add(person(u, "ADMIN")));
        admins.sort(Comparator.comparing(FloorDto.FloorPerson::displayName, String.CASE_INSENSITIVE_ORDER));
        return new FloorDto.FloorChatPeers(managers, List.copyOf(admins));
    }

    private static FloorDto.FloorPerson person(UserEntity user, String role) {
        return new FloorDto.FloorPerson(user.getId(), SectionService.displayName(user), user.getEmail(), role);
    }

    // ---------------------------------------------------------------- the statements

    /** `[classId -> card]`, in grade then name order: her Classes screen and every `sections` list on it. */
    private Map<String, FloorDto.FloorClass> cards(FloorScope.Reach reach) {
        var floors = byId(reach.floors());
        var onFloor = scope.floorOfSection(reach.floors());
        var sizes = childCounts(reach.sections().sectionIds());
        var ordered = new ArrayList<>(reach.sections().sections());
        ordered.sort(Comparator.comparingInt(ClassEntity::getGrade).thenComparing(ClassEntity::getName, String.CASE_INSENSITIVE_ORDER));
        var out = new LinkedHashMap<String, FloorDto.FloorClass>();
        for (var section : ordered) {
            var floor = floors.get(onFloor.get(section.getId()));
            out.put(section.getId(), new FloorDto.FloorClass(section.getId(), section.getName(), section.getCurriculum(),
                    section.getGrade(), sizes.getOrDefault(section.getId(), 0),
                    floor == null ? null : floor.getId(), floor == null ? null : floor.getName()));
        }
        return out;
    }

    /** `[classId -> live children]` for every section in scope: one statement, never one per class. */
    private Map<String, Integer> childCounts(List<String> sectionIds) {
        var out = new LinkedHashMap<String, Integer>();
        if (sectionIds.isEmpty()) return out;
        for (Object[] row : children.countByClassIdIn(sectionIds)) out.put((String) row[0], ((Number) row[1]).intValue());
        return out;
    }

    /** The teachers named, in display-name order, in one statement. */
    private List<UserEntity> sortedByName(java.util.Collection<String> userIds) {
        var ids = userIds.stream().distinct().toList();
        if (ids.isEmpty()) return List.of();
        var rows = new ArrayList<UserEntity>();
        users.findAllById(ids).forEach(rows::add);
        rows.sort(Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Whoever the register says nothing about yet — the roster minus the four marks it does carry. */
    private static int notMarked(quest.server.attendance.AttendanceDto.ClassAttendanceResponse day) {
        return Math.max(0, day.totalCount() - day.presentCount() - day.absentCount() - day.lateCount() - day.excusedCount());
    }

    private static Map<String, FloorEntity> byId(List<FloorEntity> rows) {
        var out = new LinkedHashMap<String, FloorEntity>();
        for (var row : rows) out.put(row.getId(), row);
        return out;
    }

    static FloorDto.FloorRef ref(FloorEntity floor) {
        return new FloorDto.FloorRef(floor.getId(), floor.getName(), floor.getAcademy());
    }
}
