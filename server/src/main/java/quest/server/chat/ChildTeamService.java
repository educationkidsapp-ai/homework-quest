package quest.server.chat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import quest.api.dto.ChatPeerRole;
import quest.api.dto.OfficeHoursStatus;
import quest.api.dto.TeamMember;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.FloorScope;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 32, first half: <strong>my child's team</strong> — `GET /children/{id}/team`. Everyone who looks after one
 * child on one page, in the order the page reads: the subject teachers with their subjects, the subject
 * coordinators, the floor supervisor, the department manager.
 *
 * <p><strong>It invents nobody.</strong> Each group comes from the reach that already decides who a parent may write
 * to — {@link TeacherScope#assignmentsOn}, {@link ChatPeers#coordinatorsOn}, {@link ChatPeers#managersOn} and
 * {@link FloorScope#supervisorsOfSection} — so every row here is a row
 * {@link ChatService#requireStaffOf} admits, and <em>Message</em> on it is the ordinary
 * `POST /children/{id}/chat/threads/{staffId}/messages`. Nobody new becomes reachable because this page exists; the
 * floor supervisor is the one change, and it is {@code requireStaffOf}'s (she could already write to the parent).
 *
 * <p><strong>No staff phone number, ever.</strong> {@link TeamMember} has no phone field and this class never reads
 * one: a row is built from a user's id and display name and nothing else. {@code officeHours} carries the
 * <em>school's</em> number ({@link OfficeHoursStatus#getSchoolPhone()}), which is the one number a parent is given,
 * and only while `chatOfficeHours` is on.
 *
 * <p><strong>Feature 33's one addition.</strong> A teacher's row carries {@code latestEvaluation} — the newest
 * evaluation of this child she has written that the parent may read, which means approved and of a released round
 * ({@link quest.server.evaluations.ChildEvaluationService#latestEvaluations}). It is a cue to open the evaluations
 * screen and carries no rating, no state and no reviewer; with `childEvaluations` off it is absent altogether.
 *
 * <p><strong>The school is the child's own</strong>, as everywhere a parent is served: she carries no tenant scope,
 * so every lookup here names {@code child.getSchoolId()} and never a parameter of the request.
 */
@Service
public class ChildTeamService {
    private final ChatService chat; private final TeacherScope scope; private final ClassRepository classes;
    private final ChatPeers peers; private final FloorScope floors; private final UserRepository users;
    private final ChatThreadRepository threads; private final OfficeHours officeHours; private final FeatureFlags flags;
    private final quest.server.evaluations.ChildEvaluationService evaluations; private final Clock clock;

    public ChildTeamService(ChatService chat, TeacherScope scope, ClassRepository classes, ChatPeers peers, FloorScope floors,
                            UserRepository users, ChatThreadRepository threads, OfficeHours officeHours, FeatureFlags flags,
                            quest.server.evaluations.ChildEvaluationService evaluations, Clock clock) {
        this.chat = chat; this.scope = scope; this.classes = classes; this.peers = peers; this.floors = floors;
        this.users = users; this.threads = threads; this.officeHours = officeHours; this.flags = flags;
        this.evaluations = evaluations; this.clock = clock;
    }

    /** One member before her name, her office hours and her thread are filled in — who she is and why she is here. */
    private record Draft(ChatPeerRole role, String staffId, String subjects) {}

    public List<TeamMember> team(Principals.Parent parent, String childId) {
        var child = chat.placed(parent, childId);                       // hers, her school's `chat` on, and placed
        String schoolId = child.getSchoolId();
        requireOn(schoolId);
        var section = classes.findOneById(child.getClassId()).orElseThrow(() -> ApiException.notFound("class"));
        var drafts = new ArrayList<Draft>();
        drafts.addAll(teachers(section));
        for (var coordinator : peers.coordinatorsOn(schoolId, section))
            drafts.add(new Draft(ChatPeerRole.COORDINATOR, coordinator.user().getId(), coordinator.subjects()));
        drafts.addAll(supervisors(schoolId, section));
        for (var manager : peers.managersOn(schoolId, section))
            drafts.add(new Draft(ChatPeerRole.MANAGERIAL, manager.getId(), null));
        return fill(schoolId, child, drafts);
    }

    /** The teachers assigned to the section, each with the subjects she holds on it, in name order. */
    private List<Draft> teachers(ClassEntity section) {
        var subjects = new LinkedHashMap<String, Set<String>>();
        for (var a : scope.assignmentsOn(section.getId()))
            subjects.computeIfAbsent(a.getTeacherId(), k -> new LinkedHashSet<>()).add(subject(a));
        return subjects.entrySet().stream()
                .map(e -> new Draft(ChatPeerRole.TEACHER, e.getKey(), String.join(", ", e.getValue())))
                .toList();
    }

    /**
     * The floor supervisor of the floor the section sits on — <strong>only</strong> while `floorSupervisor` is on,
     * and nobody at all when the class is on no floor, which is what the spec's "the row appears only when the class
     * sits on a floor" means.
     */
    private List<Draft> supervisors(String schoolId, ClassEntity section) {
        if (!flags.isOn(schoolId, FlagKeys.FLOOR_SUPERVISOR)) return List.of();
        return floors.supervisorsOfSection(schoolId, section.getId()).stream()
                .map(id -> new Draft(ChatPeerRole.FLOOR_SUPERVISOR, id, null)).toList();
    }

    /**
     * The rows, in group order and by name inside each group. Four statements whatever the size of the team: the
     * accounts, the child's threads, and — with `chatOfficeHours` on — one batched read of the office hours
     * ({@link OfficeHours#banners}). A member whose account is gone is left out rather than named with a blank.
     */
    private List<TeamMember> fill(String schoolId, quest.server.children.Entities.ChildEntity child, List<Draft> drafts) {
        if (drafts.isEmpty()) return List.of();
        String childId = child.getId();
        var ids = drafts.stream().map(Draft::staffId).distinct().toList();
        var accounts = new LinkedHashMap<String, UserEntity>();
        for (var user : users.findAllById(ids)) accounts.put(user.getId(), user);
        Map<String, String> threadIds = new LinkedHashMap<>();
        for (var thread : threads.findMessageThreadsOf(childId)) threadIds.putIfAbsent(thread.getTeacherId(), thread.getId());
        Map<String, OfficeHoursStatus> hours = flags.isOn(schoolId, FlagKeys.CHAT_OFFICE_HOURS)
                ? officeHours.banners(schoolId, ids, clock.instant()) : Map.of();
        // Feature 33: "Latest evaluation" on a teacher's row — released and approved data only, and nothing at all
        // while the school has the switch off, which is one read whatever the size of the team.
        Map<String, quest.api.dto.TeamLatestEvaluation> latest = flags.isOn(schoolId, FlagKeys.CHILD_EVALUATIONS)
                ? evaluations.latestEvaluations(child) : Map.of();
        var out = new ArrayList<TeamMember>(drafts.size());
        var seen = new LinkedHashSet<String>();
        for (var draft : drafts) {
            var user = accounts.get(draft.staffId());
            if (user == null || !seen.add(draft.staffId())) continue;   // one row per person, the first group she is in
            out.add(new TeamMember(draft.staffId(), ChatService.name(user), draft.role(), blankToNull(draft.subjects()),
                    hours.get(draft.staffId()), threadIds.get(draft.staffId()), true,
                    draft.role() == ChatPeerRole.TEACHER ? latest.get(draft.staffId()) : null));
        }
        return List.copyOf(sorted(out));
    }

    /** Group order is the page's; inside a group the rows read by name, which is the order every chooser uses. */
    private static List<TeamMember> sorted(List<TeamMember> rows) {
        rows.sort(Comparator.comparingInt((TeamMember m) -> switch (m.getRole()) {
            case TEACHER -> 0;
            case COORDINATOR -> 1;
            case FLOOR_SUPERVISOR -> 2;
            case MANAGERIAL -> 3;
            case ADMIN, PARENT -> 4;                                    // neither is ever built here
        }).thenComparing(TeamMember::getName));
        return rows;
    }

    private static String subject(TeachingAssignmentEntity a) { return a.getSubject() == null ? "" : a.getSubject().trim(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    /** The same body an unknown route gets: the page needs `childTeam`, and `chat` is checked by {@link ChatService#placed}. */
    private void requireOn(String schoolId) {
        if (!flags.isOn(schoolId, FlagKeys.CHILD_TEAM)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }
}
