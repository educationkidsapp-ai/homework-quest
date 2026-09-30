package quest.server.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * T1 (the owner's list, 2026-10-01): <strong>"teachers get Coordinator and Manager pages; coordinators get a Manager
 * page"</strong> — who supervises me, how do I reach her, and what exactly is her job. The three routes it serves
 * (`GET /teacher/coordinators`, `GET /teacher/managers`, `GET /coordinator/managers`) answer one shape,
 * {@link StaffDto.StaffContact}, because they are one screen asked from two roles.
 *
 * <p><strong>The reach is the chat reach, not a new one.</strong> The managers come from
 * {@link ChatPeers#managersForTeacher} and {@link ChatPeers#managerOptionsFor} — the very lists the staff-thread
 * writes validate against — so the directory can never offer somebody the "direct message" button would then refuse.
 * The coordinators are {@link ChatPeers#coordinatorsOn}'s rule read from the teacher's side: a coordinator belongs to
 * her when a `staff_scopes` row of that coordinator's names a subject she teaches in a track she teaches it in.
 *
 * <p><strong>Matched per section, never crossed.</strong> The pairs come off her own assignments as (subject, track,
 * grade) triples and each triple is matched whole. A teacher of maths in British grade 1 and of drama in American
 * grade 3 must not be handed the British drama coordinator, which is what matching a set of subjects against a set of
 * tracks would do (the bug `BroadcastApiTest` keeps a fixture for).
 *
 * <p><strong>Tenancy.</strong> Every lookup names {@link TenantContext#writeSchoolId()}, the caller's own school, and
 * her scope comes from {@link TeacherScope} or {@link CoordinatorScope}; no route parameter reaches any of it.
 */
@Component
public class StaffDirectory {
    static final String COORDINATOR = "coordinator", MANAGER = "manager";

    private final ChatPeers peers; private final TeacherScope teachers; private final CoordinatorScope coordinators;
    private final UserRepository users; private final TenantContext tenant; private final ChatPresence presence;

    public StaffDirectory(ChatPeers peers, TeacherScope teachers, CoordinatorScope coordinators, UserRepository users,
                          TenantContext tenant, ChatPresence presence) {
        this.peers = peers; this.teachers = teachers; this.coordinators = coordinators; this.users = users;
        this.tenant = tenant; this.presence = presence;
    }

    /** One (subject, track, grade) this teacher actually teaches — the unit a coordinator's scope is matched against. */
    private record Taught(String subject, String curriculum, int grade) {}

    /**
     * The whole rule, in one place: a `staff_scopes` row covers a triple when the subject is the same and the track
     * is either the same or the row's is blank (DR1's "both tracks"). Both directions of the directory call it, so
     * "who may I write to" and "is she one of mine" can never drift apart.
     */
    private static boolean covers(String rowSubject, String rowCurriculum, Taught taught) {
        return taught.subject().equals(normalise(rowSubject))
                && (rowCurriculum == null || rowCurriculum.isBlank() || taught.curriculum().equals(normalise(rowCurriculum)));
    }

    /**
     * T1b, the mirror: <strong>is this teacher one of mine?</strong> The coordinator side of
     * {@link #coordinatorsForTeacher}, asked of a thread a teacher opened with her — `/coordinator/chat/**` has to
     * answer it to let her read and reply, and it must be the same question the write asked, or a thread would exist
     * that one of its two parties cannot open. Her scope comes from {@link CoordinatorScope#scopesOf}, so the
     * architecture test sees the check.
     */
    public boolean coversTeacher(Principals.User coordinator, String teacherId) {
        if (teacherId == null) return false;
        var taught = taughtByUser(teacherId);
        if (taught.isEmpty()) return false;
        for (var scope : coordinators.scopesOf(coordinator))
            for (Taught t : taught) if (covers(scope.subject(), scope.curriculum(), t)) return true;
        return false;
    }

    /** T1b: the coordinators this teacher may open a thread with, by id — the directory as the write's guest list. */
    public Set<String> coordinatorIdsForTeacher(Principals.User caller) {
        return coordinatorsForTeacher(caller).stream().map(StaffDto.StaffContact::userId).collect(Collectors.toSet());
    }

    /**
     * `GET /teacher/coordinators`: the coordinators whose scope covers any (subject, track) pair of hers, each with the
     * grades of hers that put her on the list — so a teacher of grade 1 maths in the British track reads
     * "Coordinator · Grade 1 · Math · British", and a coordinator who covers two of her grades is named with both.
     */
    public List<StaffDto.StaffContact> coordinatorsForTeacher(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var mine = taughtBy(caller);
        if (mine.isEmpty()) return List.of();
        var staff = users.findBySchoolIdAndRole(schoolId, CoordinatorScope.ROLE);
        if (staff.isEmpty()) return List.of();
        var rows = peers.scopeRowsOf(schoolId, staff);
        var out = new ArrayList<StaffDto.StaffContact>();
        for (var person : staff) {
            var grades = new TreeSet<Integer>(); var subjects = new LinkedHashSet<String>(); var tracks = new LinkedHashSet<String>();
            for (StaffScopeEntity row : rows.getOrDefault(person.getId(), List.of()))
                for (Taught t : mine)
                    if (covers(row.getSubject(), row.getCurriculum(), t)) {
                        grades.add(t.grade()); subjects.add(t.subject()); tracks.add(t.curriculum());
                    }
            if (grades.isEmpty()) continue;
            String subject = String.join(", ", subjects), track = tracks.iterator().next();
            var job = new StaffDto.StaffJobParts(COORDINATOR, List.copyOf(grades), subject, track);
            out.add(contact(person, job, coordinatorJob(grades, subject, track), track, subject));
        }
        out.sort(java.util.Comparator.comparing(StaffDto.StaffContact::displayName));
        return List.copyOf(out);
    }

    /** `GET /teacher/managers`: the managers of the departments she teaches in, as contacts (MG1's chooser grown up). */
    public List<StaffDto.StaffContact> managersForTeacher(Principals.User caller) {
        return managers(peers.managersForTeacher(TeacherScope.require(caller)));
    }

    /** `GET /coordinator/managers`: the managers whose department intersects her scope (RM1's chooser grown up). */
    public List<StaffDto.StaffContact> managersForCoordinator(Principals.User caller) {
        return managers(peers.managerOptionsFor(CoordinatorScope.require(caller)));
    }

    private List<StaffDto.StaffContact> managers(List<ChatPeers.Manager> found) {
        return found.stream().map(m -> {
            var job = new StaffDto.StaffJobParts(MANAGER, List.of(), null, m.curriculum());
            return contact(m.user(), job, title(m.curriculum()) + " department manager", m.curriculum(), null);
        }).toList();
    }

    /**
     * Her own timetable as triples: the assignments name the subject, the section names the track and the grade. A
     * section a row points at that no longer exists is skipped rather than guessed at.
     */
    private List<Taught> taughtBy(Principals.User caller) {
        var me = TeacherScope.require(caller);
        var sections = new LinkedHashMap<String, ClassEntity>();
        for (var section : teachers.classesOf(me)) sections.put(section.getId(), section);
        var out = new LinkedHashSet<Taught>();
        for (var assignment : teachers.assignmentsOf(me)) {
            var section = sections.get(assignment.getClassId());
            if (section != null && assignment.getSubject() != null)
                out.add(new Taught(normalise(assignment.getSubject()), normalise(section.getCurriculum()), section.getGrade()));
        }
        return List.copyOf(out);
    }

    /**
     * The same triples for a teacher the caller is not — what a coordinator asks about the other end of her thread.
     * Both reads are {@link TeacherScope}'s, so `staff_scopes` and `teaching_assignments` keep their single owner, and
     * a section that has been deleted since the assignment was written is skipped rather than guessed at.
     */
    private List<Taught> taughtByUser(String teacherId) {
        var out = new LinkedHashSet<Taught>();
        for (var assignment : teachers.assignmentsOf(teacherId)) {
            if (assignment.getSubject() == null) continue;
            ClassEntity section;
            try { section = teachers.section(assignment.getClassId()); } catch (RuntimeException gone) { continue; }
            out.add(new Taught(normalise(assignment.getSubject()), normalise(section.getCurriculum()), section.getGrade()));
        }
        return List.copyOf(out);
    }

    private StaffDto.StaffContact contact(UserEntity person, StaffDto.StaffJobParts job, String sentence, String curriculum, String subjects) {
        return new StaffDto.StaffContact(person.getId(), ChatService.name(person), person.getEmail(), person.getRole(), sentence,
                job, person.getPhone(), curriculum, subjects, presence.userOnline(person.getId()));
    }

    /** "Coordinator · Grade 1 · Math · British", or "Coordinator · Grades 1, 2 · Math · British" for two of hers. */
    private static String coordinatorJob(TreeSet<Integer> grades, String subject, String curriculum) {
        String which = grades.size() == 1 ? "Grade " + grades.first()
                : "Grades " + String.join(", ", grades.stream().map(String::valueOf).toList());
        return "Coordinator · " + which + " · " + title(subject) + " · " + title(curriculum);
    }

    private static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    /** The English fallback sentence is written in sentence case; the client localises from `jobParts` instead. */
    private static String title(String value) {
        if (value == null || value.isBlank()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
