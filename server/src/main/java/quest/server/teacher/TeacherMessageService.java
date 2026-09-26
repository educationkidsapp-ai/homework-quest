package quest.server.teacher;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.NotificationKind;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.notifications.NotificationService;

/**
 * A teacher writing to her school's coordinator (U1 item 2) — one message, one direction.
 *
 * <p>There was no path for this. `TeacherQuestionController` is a teacher asking her <em>children</em> something,
 * announcements go to parents, and chat is per child with a parent at the other end; the complaints channel the
 * product has runs the other way, from a parent to the school. So this is the smallest thing that carries a
 * teacher's sentence to the people who handle the school's messages: a notification row per MANAGERIAL user of her
 * own school, which is already the dashboard's event channel — the bell shows it, `/me/notifications` lists it, and
 * the socket delivers it to a coordinator who is signed in while she writes.
 *
 * <p><strong>Her words travel as the body.</strong> The dashboard translates a notification from its `kind` and
 * falls back to the server's text when it has no sentence for it, which is exactly what is wanted here: the title is
 * localised ("Message from a teacher"), and the body is what she typed, in her language.
 *
 * <p><strong>Scope.</strong> The school is resolved through {@link quest.server.tenancy.TeacherScope#assignmentsOf}
 * — the school her own teaching assignments are in — and the recipients are that school's coordinators and nobody
 * else. Not `writeSchoolId()` on its own: that is a claim on her token, and `/teacher/**` resolves what a teacher
 * reaches through her assignments (§2, `TeacherScopeArchitectureTest`), so the same rule applies to the one school
 * this route names. A teacher who holds no assignment yet has no school that way; she falls back to the scope's own
 * write school, which for a TEACHER principal is that same token school and can never be another one.
 *
 * <p>A school with no MANAGERIAL account at all is a 409 rather than a silent success, because "sent" over a message
 * nobody received is the one answer she must not get.
 */
@Service
public class TeacherMessageService {
    private static final String MANAGERIAL = "MANAGERIAL";

    private final UserRepository users;
    private final NotificationService notifications;
    private final TeacherAccess access;

    public TeacherMessageService(UserRepository users, NotificationService notifications, TeacherAccess access) {
        this.users = users; this.notifications = notifications; this.access = access;
    }

    @Transactional
    public TeacherDto.CoordinatorMessageResult toCoordinator(Principals.User caller, String body) {
        String schoolId = schoolOf(caller);
        List<UserEntity> coordinators = users.findBySchoolIdAndRole(schoolId, MANAGERIAL).stream()
                .filter(user -> !"disabled".equals(user.getStatus()))
                .toList();
        if (coordinators.isEmpty())
            throw ApiException.conflict("no_coordinator", "Your school has no coordinator account yet. Ask your admin to add one.");

        String title = "Message from " + nameOf(caller);
        for (UserEntity coordinator : coordinators)
            notifications.notify(schoolId, coordinator.getId(), NotificationKind.TEACHER_MESSAGE, title, body, null, null);
        return new TeacherDto.CoordinatorMessageResult(coordinators.size());
    }

    /**
     * The school her assignments are in.
     *
     * `TeacherScope.assignmentsOf` is the narrowing call every other `/teacher/**` handler goes through, and it is
     * the one that decides whose school this is — the token is what she claims, an assignment is what the school
     * says. The fallback is for a teacher who has been created but not yet given a class.
     */
    private String schoolOf(Principals.User caller) {
        return access.assignmentsOf(caller).stream()
                .map(quest.server.tenancy.Entities.TeachingAssignmentEntity::getSchoolId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseGet(access::writeSchoolId);
    }

    /** Her display name, or the address behind the account — never an empty "Message from". */
    private String nameOf(Principals.User caller) {
        return users.findById(caller.userId())
                .map(user -> user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getEmail() : user.getDisplayName())
                .orElse(caller.email());
    }
}
