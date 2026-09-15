package quest.server.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.DashboardDto;
import quest.server.auth.Entities;
import quest.server.auth.InviteRepository;
import quest.server.auth.Principals;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.config.QuestProperties;
import quest.server.mail.OutgoingMail;
import quest.server.schools.SchoolService;

/**
 * Invites (§5): the Admin (or a Managerial user in their own school) names an address and a role, the person gets a
 * one-time link that lives 7 days. The account row is created straight away with status `invited` — sign-in refuses
 * it until the link is accepted — so the teacher profile that came with the invite has somewhere to live.
 */
@Service
public class InviteService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteRepository invites; private final UserRepository users; private final TeacherRepository teachers;
    private final SchoolService schools; private final PasswordEncoder encoder; private final OutgoingMail mails;
    private final AuditService audit; private final AuthService auth; private final Json json; private final Duration ttl;

    public InviteService(InviteRepository invites, UserRepository users, TeacherRepository teachers, SchoolService schools,
                         PasswordEncoder encoder, OutgoingMail mails, AuditService audit, AuthService auth, Json json, QuestProperties props) {
        this.invites = invites; this.users = users; this.teachers = teachers; this.schools = schools; this.encoder = encoder;
        this.mails = mails; this.audit = audit; this.auth = auth; this.json = json;
        this.ttl = Duration.ofDays(props.auth().inviteDays() <= 0 ? 7 : props.auth().inviteDays());
    }

    @Transactional
    public UserDto.Invite create(Principals.User caller, String schoolId, UserDto.CreateInviteRequest request) {
        if (!caller.isAdmin() && !schoolId.equals(caller.schoolId())) throw ApiException.forbidden("You can only invite people into your own school.");
        var school = schools.require(schoolId);
        String role = request.role().trim().toUpperCase(Locale.ROOT);
        if (!UserService.SCHOOL_ROLES.contains(role)) throw ApiException.badRequest("A school user is a TEACHER or a MANAGERIAL.");
        String email = AuthService.normalise(request.email());

        // Across schools, and by id: `users` is filtered to the caller's school now, but `email` is unique platform-wide,
        // so a Managerial caller has to be told the address is taken rather than run into the unique index.
        var user = users.findIdByEmailAcrossSchools(email).flatMap(users::findById).orElse(null);
        if (user != null && !"invited".equals(user.getStatus())) throw ApiException.badRequest("That address already has an account.");
        if (user != null && !schoolId.equals(user.getSchoolId()) && !caller.isAdmin()) throw ApiException.badRequest("That address already has an account.");
        if (user == null) {
            user = new Entities.UserEntity();
            user.setId(UUID.randomUUID().toString()); user.setEmail(email); user.setCreatedAt(Instant.now());
        }
        user.setSchoolId(schoolId); user.setRole(role); user.setStatus("invited"); user.setMustChangePassword(false);
        user.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));      // unusable until the invite is accepted
        if (request.teacherProfile() != null && request.teacherProfile().displayName() != null) user.setDisplayName(request.teacherProfile().displayName().trim());
        if (request.teacherProfile() != null && request.teacherProfile().photoUrl() != null) user.setPhotoUrl(request.teacherProfile().photoUrl());
        user.setUpdatedAt(Instant.now());
        users.save(user);
        if ("TEACHER".equals(role)) saveTeacherProfile(user.getId(), request.teacherProfile());

        String token = freshToken();
        var invite = new Entities.InviteEntity();
        invite.setId(UUID.randomUUID().toString()); invite.setSchoolId(schoolId); invite.setEmail(email); invite.setRole(role);
        invite.setTokenHash(hash(token)); invite.setInvitedBy(caller.userId());
        invite.setExpiresAt(Instant.now().plus(ttl)); invite.setCreatedAt(Instant.now());
        invites.save(invite);

        mails.invite(email, school.getName(), role, token);          // leaves after this transaction commits, off the request thread
        audit.record(caller.userId(), "user.invite", "user", user.getId(), schoolId, Map.of("email", email, "role", role));
        return toDto(invite);
    }

    /** Public: what the accept-invite page shows. An expired or already-used link is 410, so the page can say so. */
    public UserDto.InviteInfo info(String token) {
        var invite = open(token);
        return new UserDto.InviteInfo(invite.getEmail(), invite.getRole(),
                invite.getSchoolId() == null ? null : schools.require(invite.getSchoolId()).getName(), invite.getExpiresAt().toEpochMilli());
    }

    /**
     * Public: sets the password, activates the account and signs the person straight in. Only an account that is still
     * `invited` can be accepted — otherwise an unused link would stay a role- and school-changing password reset for
     * the rest of its 7 days, on an account that has meanwhile gone `active` some other way.
     */
    @Transactional
    public DashboardDto.SignInResponse accept(String token, UserDto.AcceptInviteRequest request) {
        AuthService.requireStrong(request.password());
        var invite = open(token);
        var user = users.findByEmailIgnoreCase(invite.getEmail()).orElseThrow(() -> ApiException.notFound("user"));
        if (!"invited".equals(user.getStatus())) throw gone("That invitation has already been used.");
        user.setPasswordHash(encoder.encode(request.password()));
        user.setStatus("active"); user.setMustChangePassword(false);
        if (request.displayName() != null && !request.displayName().isBlank()) user.setDisplayName(request.displayName().trim());
        user.setSchoolId(invite.getSchoolId()); user.setRole(invite.getRole());
        user.setLastLoginAt(Instant.now()); user.setUpdatedAt(Instant.now());
        users.save(user);
        consume(invite);
        audit.record(user.getId(), "user.inviteAccepted", "user", user.getId(), user.getSchoolId(), Map.of("role", user.getRole()));
        return auth.session(user);
    }

    /** The link is spent, and so is every other one still open for that address. */
    private void consume(Entities.InviteEntity accepted) {
        Instant now = Instant.now();
        accepted.setAcceptedAt(now);
        invites.save(accepted);
        for (var sibling : invites.findByEmailIgnoreCaseAndAcceptedAtIsNull(accepted.getEmail())) {
            sibling.setAcceptedAt(now);
            invites.save(sibling);
        }
    }

    private Entities.InviteEntity open(String token) {
        var invite = invites.findByTokenHash(hash(token)).orElseThrow(() -> gone("That invitation link is not valid."));
        if (invite.getAcceptedAt() != null) throw gone("That invitation has already been used.");
        if (invite.getExpiresAt().isBefore(Instant.now())) throw gone("That invitation has expired; ask for a new one.");
        return invite;
    }

    private void saveTeacherProfile(String userId, UserDto.TeacherProfileInput profile) {
        var row = teachers.findById(userId).orElseGet(() -> { var t = new Entities.TeacherEntity(); t.setUserId(userId); return t; });
        if (profile != null) {
            row.setSubjectsJson(json.write(profile.subjects() == null ? List.of() : profile.subjects()));
            row.setGradesJson(json.write(profile.grades() == null ? List.of() : profile.grades()));
            row.setCurriculum(profile.curriculum());
            row.setBioEn(profile.bioEn()); row.setBioAr(profile.bioAr());
        }
        row.setUpdatedAt(Instant.now());
        teachers.save(row);
    }

    static UserDto.Invite toDto(Entities.InviteEntity invite) {
        return new UserDto.Invite(invite.getId(), invite.getEmail(), invite.getRole(), invite.getSchoolId(), invite.getInvitedBy(),
                invite.getExpiresAt().toEpochMilli(), invite.getAcceptedAt() == null ? null : invite.getAcceptedAt().toEpochMilli(),
                invite.getCreatedAt().toEpochMilli());
    }

    private static ApiException gone(String message) { return new ApiException(HttpStatus.GONE, "gone", message); }

    private static String freshToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String hash(String token) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(String.valueOf(token).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
