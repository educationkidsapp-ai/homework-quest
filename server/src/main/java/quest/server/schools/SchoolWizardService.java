package quest.server.schools;

import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.DashboardDto;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.flags.FlagService;
import quest.server.platform.ThemeService;
import quest.server.users.InviteService;
import quest.server.users.UserDto;
import quest.server.users.UserService;

/**
 * §6 screen 4: the New school wizard's submit. Its four steps — name and join code, curriculum and grade options,
 * theme, feature flags, first Managerial user — arrive as one body and are applied as <strong>one transaction</strong>:
 * a theme whose contrast fails, a flag key that does not exist or an address that is already taken leaves no school
 * behind at all, rather than a half-made tenant the Admin has to clean up by hand.
 *
 * <p>Everything here goes through the services that own each piece, so the wizard inherits their rules rather than
 * repeating them: {@link ThemeService#validated} refuses a theme below 4.5:1 (§3), {@link FlagService#setAll} refuses
 * an unknown key and writes an audit row per flag (§4), {@link UserService#create} and {@link InviteService#create}
 * enforce the platform-wide uniqueness of the address and the 10-character password (§5).
 *
 * <p>The one thing that is not transactional is the invitation email: {@link InviteService} hands it to
 * {@link quest.server.mail.OutgoingMail}, which sends after the commit and off the request thread, so a rollback
 * cannot have already mailed a link into a school that does not exist.
 */
@Service
public class SchoolWizardService {
    private static final String MANAGERIAL = "MANAGERIAL";

    private final SchoolService schools; private final ThemeService themes; private final FlagService flags;
    private final UserService users; private final InviteService invites; private final UserRepository userRows;
    private final AuditService audit;

    public SchoolWizardService(SchoolService schools, ThemeService themes, FlagService flags, UserService users,
                               InviteService invites, UserRepository userRows, AuditService audit) {
        this.schools = schools; this.themes = themes; this.flags = flags; this.users = users; this.invites = invites;
        this.userRows = userRows; this.audit = audit;
    }

    /**
     * Creates the school, its theme, its flag overrides and its first Managerial user. With a password the account is
     * active straight away and must change it at the first sign-in; without one an invitation goes out and the
     * account stays `invited` until the link is used.
     */
    @Transactional
    public SchoolDto.SchoolWizardResponse create(Principals.User actor, SchoolDto.SchoolWizardRequest request) {
        if (actor == null) throw ApiException.unauthorized("Sign in first.");
        var manager = request.manager();
        if (manager == null || manager.email() == null || manager.email().isBlank())
            throw ApiException.badRequest("The first managerial user needs an email address.");

        var school = schools.create(actor.userId(), request.school());

        // The theme before the user: it is the step most likely to be refused (§3 contrast), and refusing it early
        // means the rollback has less to undo — though the transaction would undo all of it either way.
        var theme = request.theme() == null ? null : themes.save(actor, school.id(), request.theme());
        var effectiveFlags = flags.setAll(actor, school.id(), request.flags() == null ? Map.of() : request.flags());

        boolean invited = manager.password() == null || manager.password().isBlank();
        DashboardDto.DashboardUser user;
        if (invited) {
            var invite = invites.create(actor, school.id(),
                    new UserDto.CreateInviteRequest(manager.email(), MANAGERIAL, profileFor(manager)));
            user = userRows.findByEmailIgnoreCase(invite.email())
                    .map(row -> DashboardDto.of(row, null, null, school.name()))
                    .orElseThrow(() -> new IllegalStateException("the invite created no user row for " + invite.email()));
        } else {
            var created = users.create(actor, school.id(),
                    new UserDto.CreateUserRequest(manager.email(), MANAGERIAL, manager.password(), manager.displayName(), null));
            user = new DashboardDto.DashboardUser(created.id(), created.email(), created.role(), created.schoolId(),
                    created.status(), created.displayName(), created.photoUrl(), created.language(),
                    created.mustChangePassword(), created.lastLoginAt(), created.createdAt(), null, null, school.name(), null);
        }

        audit.record(actor.userId(), "school.wizard", "school", school.id(), school.id(),
                Map.of("manager", manager.email().trim().toLowerCase(Locale.ROOT), "invited", invited,
                        "flags", effectiveFlags.size(), "theme", theme != null));
        // The school is re-read so its counts (the Overview tab's four numbers) include the user just made.
        return new SchoolDto.SchoolWizardResponse(schools.toDto(schools.require(school.id())), user, invited, theme, effectiveFlags);
    }

    /** A Managerial user has no teacher profile; the display name is the only field an invite can carry for her. */
    private static UserDto.TeacherProfileInput profileFor(SchoolDto.WizardManagerInput manager) {
        return manager.displayName() == null || manager.displayName().isBlank() ? null
                : new UserDto.TeacherProfileInput(manager.displayName(), null, null, null, null, null, null);
    }
}
