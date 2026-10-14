package quest.server.staff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatThread;
import quest.api.staff.StaffMe;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatService;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.schools.SchoolService;
import quest.server.tenancy.TenantContext;

/**
 * Feature 13: what the staff app asks for, answered entirely out of the services the dashboard already calls.
 *
 * <p><strong>This is a facade and holds no rules of its own.</strong> Every chat route below hands the work to
 * {@link ChatService} under the caller's own role, so the scope that decides what a teacher, a coordinator, a manager
 * or an Admin reaches is the one the dashboard is already checked against ({@code TeacherScope},
 * {@code CoordinatorScope}, {@code ManagerScope}, and the Admin's "a thread she is on"), and the rows are the same
 * rows. A second set of chat rules for the phone is exactly what this avoids.
 *
 * <p><strong>Nothing takes a school from the request.</strong> It is {@link TenantContext}'s, resolved from the token
 * (the Admin's from `X-School-Id`, as everywhere else).
 *
 * <p><strong>A WORKER has no conversations yet</strong> — nobody is on the other end of one — so her Messages tab is
 * an empty list and a thread named by id is 404. She still has `GET /staff/me`, her notifications and her phone,
 * which is the whole of feature 13 for her; check-in is feature 15.
 */
@Service
public class StaffService {
    private final ChatService chat; private final UserRepository users; private final SchoolService schools;
    private final FeatureFlags flags; private final TenantContext tenant; private final java.time.Clock clock;

    public StaffService(ChatService chat, UserRepository users, SchoolService schools, FeatureFlags flags, TenantContext tenant,
                        java.time.Clock clock) {
        this.chat = chat; this.users = users; this.schools = schools; this.flags = flags; this.tenant = tenant; this.clock = clock;
    }

    /**
     * `GET /staff/me`: her name, role and number, her school, and that school's effective flags — so the app gates
     * staff screens from the staff session rather than from whichever school a child of hers happens to be in.
     */
    public StaffMe me(Principals.User caller) {
        var user = users.findById(caller.userId()).orElseThrow(() -> ApiException.notFound("user"));
        String schoolId = tenant.writeSchoolId();
        String name = user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getEmail() : user.getDisplayName();
        return new StaffMe(user.getId(), name, user.getRole(), user.getPhone(), schoolId,
                schools.namesOf(List.of(schoolId)).get(schoolId), flags.effective(schoolId), user.isAfterHoursAlerts());
    }

    /**
     * Feature 19: `PUT /staff/me/preferences` — her own choice to keep normal alerts for parent messages outside her
     * office hours, which is hers alone and so takes no school and no target from the request. Answers the updated
     * {@link StaffMe}, so the Account screen redraws from one response.
     */
    @org.springframework.transaction.annotation.Transactional
    public StaffMe preferences(Principals.User caller, boolean afterHoursAlerts) {
        var user = users.findById(caller.userId()).orElseThrow(() -> ApiException.notFound("user"));
        user.setAfterHoursAlerts(afterHoursAlerts); user.setUpdatedAt(java.time.Instant.now(clock));
        users.save(user);
        return me(caller);
    }

    /**
     * `GET /staff/chat/threads`: one inbox, merged from whichever lists her role has and keyed by thread id, newest
     * activity first.
     *
     * <p>A teacher is the only role whose conversations come from two lists — the parents of her sections and her
     * supervisors — and the dashboard shows them on two screens; the phone shows one, so they are merged here and
     * de-duplicated by id, which also covers the row a coordinator and a teacher share.
     */
    public List<ChatThread> threads(Principals.User caller) {
        var merged = new LinkedHashMap<String, ChatThread>();
        for (ChatThread row : listsFor(caller)) if (row.getId() != null) merged.putIfAbsent(row.getId(), row);
        var rows = new ArrayList<>(merged.values());
        rows.sort((a, b) -> Long.compare(activity(b), activity(a)));
        return List.copyOf(rows);
    }

    public List<ChatMessage> messages(Principals.User caller, String threadId, String before, String since, Integer limit) {
        return switch (role(caller)) {
            case TEACHER -> chat.teacherThreadMessages(caller, threadId, before, since, limit);
            case STAFF -> chat.staffMessages(caller, threadId, before, since, limit);
            case WORKER -> throw ApiException.notFound("thread");
        };
    }

    public ChatMessage send(Principals.User caller, String threadId, String body, List<String> files, String clientId) {
        return switch (role(caller)) {
            case TEACHER -> chat.teacherThreadSend(caller, threadId, body, files, clientId);
            case STAFF -> chat.staffSend(caller, threadId, body, files, clientId);
            case WORKER -> throw ApiException.notFound("thread");
        };
    }

    public ChatReadReceipt read(Principals.User caller, String threadId) {
        return switch (role(caller)) {
            case TEACHER -> chat.teacherThreadRead(caller, threadId);
            case STAFF -> chat.staffRead(caller, threadId);
            case WORKER -> throw ApiException.notFound("thread");
        };
    }

    // ---------------------------------------------------------------- helpers

    /** Which of {@link ChatService}'s three doors this caller goes through. */
    private enum Door { TEACHER, STAFF, WORKER }

    private static Door role(Principals.User caller) {
        return switch (caller.role() == null ? "" : caller.role()) {
            case "TEACHER" -> Door.TEACHER;
            case "WORKER" -> Door.WORKER;
            default -> Door.STAFF;
        };
    }

    private List<ChatThread> listsFor(Principals.User caller) {
        var rows = new ArrayList<ChatThread>();
        switch (caller.role() == null ? "" : caller.role()) {
            case "TEACHER" -> { rows.addAll(chat.teacherThreads(caller)); rows.addAll(chat.teacherStaffThreads(caller)); }
            case "COORDINATOR" -> rows.addAll(chat.coordinatorThreads(caller, null));
            case "MANAGERIAL" -> rows.addAll(chat.managerThreads(caller, null));
            case "ADMIN" -> rows.addAll(chat.adminThreads(caller));
            default -> { }                                                      // WORKER: nobody to talk to yet
        }
        return rows;
    }

    /** When the thread last moved: its last message, or nothing at all for a row nobody has written on. */
    private static long activity(ChatThread row) {
        return row.getLastMessage() == null ? 0L : row.getLastMessage().getCreatedAt();
    }
}
