package quest.server.broadcasts;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.NotificationKind;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.broadcasts.Entities.BroadcastEntity;
import quest.server.broadcasts.Entities.BroadcastReadEntity;
import quest.server.children.ChildService;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.coordinator.CoordinatorAnnouncementService;
import quest.server.notifications.NotificationService;
import quest.server.platform.SafeText;
import quest.server.teacher.TeacherDto;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * RM2 (DR6): <strong>one broadcast feature, two composers, three audiences.</strong> A manager posts the weekly plan,
 * an announcement or an event to her department — any combination of its parents, its teachers and its coordinators —
 * and a coordinator posts an announcement or an event to the parents of the classes she coordinates. The row carries
 * the audience, so both composers write the same shape and every reader answers the same question of it.
 *
 * <p><strong>Nothing reads a school, a track or a section from the request.</strong> The author's scope is
 * {@link ManagerScope} or {@link CoordinatorScope}: an empty `sectionIds` means "every section of my scope", and a
 * named one is resolved section by section through `requireSection`, so another department's is 403 and another
 * school's 404. `school_id` is {@link TenantContext#writeSchoolId()}.
 *
 * <p><strong>Delivery.</strong> A dashboard recipient gets an E2 notification row and the `broadcast.posted` frame on
 * her socket, plus `GET /me/broadcasts`; a parent has no bell and reads `GET /children/{id}/broadcasts`, whose rows are
 * matched to that child's own section. A coordinator's announcement additionally keeps writing the `announcements`
 * rows it always wrote ({@link CoordinatorAnnouncementService#mirror}), so the app screen phase 2 shipped keeps
 * showing it while RM4 moves the app to this feed: `POST /coordinator/announcements` is now a second door onto this
 * service rather than a second feature.
 *
 * <p><strong>A weekly plan is one per week per department</strong> and re-posting replaces it — the previous row and
 * its read marks are deleted, so the recipients see one plan and an unread badge for the new one.
 */
@Service
public class BroadcastService {
    /** `broadcasts.kind`, three words. */
    public static final String WEEKLY_PLAN = "weekly_plan", ANNOUNCEMENT = "announcement", EVENT = "event";
    /** `broadcasts.audience_roles`, comma-separated: whom a row is for. A coordinator's is always the parents. */
    public static final String PARENTS = "parents", TEACHERS = "teachers", COORDINATORS = "coordinators";
    /** A note, not an essay, and never scheduled to outlive a school year — `AnnouncementService`'s two numbers. */
    private static final int MAX_BODY = 1000, MAX_TITLE = 120, MAX_LIFETIME_DAYS = 400, PAGE = 50;

    private final BroadcastRepository rows; private final BroadcastReadRepository reads;
    private final ManagerScope managers; private final CoordinatorScope coordinators; private final TeacherScope teachers;
    private final StaffScopeRepository scopes; private final UserRepository users; private final ChildService childService;
    private final NotificationService notifications; private final CoordinatorAnnouncementService announcements;
    private final TenantContext tenant; private final Clock clock;

    public BroadcastService(BroadcastRepository rows, BroadcastReadRepository reads, ManagerScope managers,
                           CoordinatorScope coordinators, TeacherScope teachers, StaffScopeRepository scopes,
                           UserRepository users, ChildService childService, NotificationService notifications,
                           CoordinatorAnnouncementService announcements, TenantContext tenant, Clock clock) {
        this.rows = rows; this.reads = reads; this.managers = managers; this.coordinators = coordinators;
        this.teachers = teachers; this.scopes = scopes; this.users = users; this.childService = childService;
        this.notifications = notifications; this.announcements = announcements; this.tenant = tenant; this.clock = clock;
    }

    // ---------------------------------------------------------------- the manager composes (POST /management/broadcasts)

    /**
     * Her department's broadcast. `sectionIds` empty is the whole department, which is what `curriculum` then carries;
     * a manager who holds two departments and names no section is asked to name them, because one row targets one
     * track.
     */
    @Transactional
    public BroadcastDto.View managerPost(Principals.User caller, BroadcastDto.CreateRequest request) {
        String schoolId = tenant.writeSchoolId();
        var departments = managers.departments(caller);
        var reach = managers.reach(caller);
        boolean named = named(request.sectionIds());
        var targets = named ? request.sectionIds().stream().distinct().map(id -> managers.requireSection(caller, id)).toList() : reach.sections();
        if (targets.isEmpty()) throw ApiException.badRequest("You manage no class yet, so there is nobody to tell.");
        String curriculum = named ? oneTrack(targets) : one(departments);
        var audience = audience(request.audience());
        var row = write(schoolId, caller.userId(), ManagerScope.ROLE, request, curriculum, named ? targets : null, audience);
        fanOut(row, schoolId, audience, targets, reach.assignments());
        return view(row, displayName(caller.userId()), true);
    }

    /** `GET /management/broadcasts`: what she posted, newest first, expired rows included — her own composer list. */
    public List<BroadcastDto.View> managerPosts(Principals.User caller) {
        managers.departments(caller);
        return mine(caller.userId());
    }

    // ---------------------------------------------------------------- the coordinator composes (POST /coordinator/broadcasts)

    /** The broadcast and, for an announcement, the `announcements` rows the app's shipped screen reads. */
    public record CoordinatorPost(BroadcastDto.View broadcast, List<TeacherDto.Announcement> announcements) {}

    /**
     * The parents of the classes she coordinates. A weekly plan is the manager's to write (DR6: the plan is the
     * department's), so `weekly_plan` is 400 here rather than a plan nobody else's week lines up with.
     */
    @Transactional
    public CoordinatorPost coordinatorPost(Principals.User caller, BroadcastDto.CreateRequest request) {
        String schoolId = tenant.writeSchoolId();
        if (WEEKLY_PLAN.equals(kind(request.kind()))) throw ApiException.badRequest("A weekly plan is posted by the manager of the department.");
        boolean named = named(request.sectionIds());
        var targets = named ? request.sectionIds().stream().distinct().map(id -> coordinators.requireSection(caller, id)).toList()
                : coordinators.sectionsOf(caller);
        if (targets.isEmpty()) throw ApiException.badRequest("You coordinate no class yet, so there is nobody to tell.");
        var row = write(schoolId, caller.userId(), CoordinatorScope.ROLE, request, null, targets, List.of(PARENTS));
        var mirrored = ANNOUNCEMENT.equals(row.getKind())
                ? announcements.mirror(caller, targets, row.getBodyEn(), row.getBodyAr(), row.getExpiresAt()) : List.<TeacherDto.Announcement>of();
        return new CoordinatorPost(view(row, displayName(caller.userId()), true), mirrored);
    }

    /** `GET /coordinator/broadcasts`: hers, newest first. Her scope is read so the route is hers and not a teacher's. */
    public List<BroadcastDto.View> coordinatorPosts(Principals.User caller) {
        coordinators.scopesOf(caller);
        return mine(caller.userId());
    }

    // ---------------------------------------------------------------- the dashboard reads (GET /me/broadcasts)

    /**
     * What this dashboard user is an audience of, newest first, with her unread count. One page of the school's live
     * rows is filtered by her own reach — her assignments (TEACHER), her subjects' tracks (COORDINATOR), her
     * departments (MANAGERIAL) — and the platform ADMIN scoped to a school reads all of it (D6).
     */
    public BroadcastDto.Feed forStaff(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var mine = reach(caller);
        var visible = rows.live(schoolId, clock.instant(), PageRequest.of(0, PAGE)).stream()
                .filter(b -> caller.userId().equals(b.getAuthorUserId()) || mine.sees(b)).toList();
        return feed(visible, caller.userId());
    }

    /** One row marked read by a dashboard user; a row she is not an audience of is 404, not 403. */
    @Transactional
    public BroadcastDto.View staffRead(Principals.User caller, String broadcastId) {
        var mine = reach(caller);
        var row = rows.findOneById(broadcastId).filter(b -> caller.userId().equals(b.getAuthorUserId()) || mine.sees(b))
                .orElseThrow(() -> ApiException.notFound("broadcast"));
        mark(row, caller.userId());
        return view(row, displayName(row.getAuthorUserId()), true);
    }

    // ---------------------------------------------------------------- the app reads (GET /children/{id}/broadcasts)

    /**
     * Every broadcast for the parents of the section this child sits in, newest first, with the unread count for that
     * child. It is named by child rather than by parent on purpose: a flagged parent route has to say which child it is
     * about ({@code FeatureFlagInterceptor} fails closed otherwise, P4.0), and the school is then the child's own —
     * `AnnouncementService.forChild`'s rule, since a parent carries no tenant scope. A child on no section yet gets an
     * empty feed rather than a refusal: the app shows no card.
     */
    public BroadcastDto.Feed forChild(Principals.Parent parent, String childId) {
        var kid = childService.owned(childId, parent);
        if (kid.getClassId() == null) return new BroadcastDto.Feed(0, List.of());
        var visible = rows.live(kid.getSchoolId(), clock.instant(), PageRequest.of(0, PAGE)).stream()
                .filter(b -> forChild(b, kid)).toList();
        return feed(visible, parent.parentId());
    }

    /** One row marked read by a parent; a row this child is not an audience of is 404, not 403. */
    @Transactional
    public BroadcastDto.View parentRead(Principals.Parent parent, String childId, String broadcastId) {
        var kid = childService.owned(childId, parent);
        var row = rows.findOneById(broadcastId)
                .filter(b -> kid.getClassId() != null && b.getSchoolId().equals(kid.getSchoolId()) && forChild(b, kid))
                .orElseThrow(() -> ApiException.notFound("broadcast"));
        mark(row, parent.parentId());
        return view(row, displayName(row.getAuthorUserId()), true);
    }

    // ---------------------------------------------------------------- writing

    private BroadcastEntity write(String schoolId, String authorId, String authorRole, BroadcastDto.CreateRequest request,
                                  String curriculum, List<ClassEntity> sections, List<String> audience) {
        String kind = kind(request.kind());
        String bodyEn = SafeText.plainText(request.bodyEn(), "bodyEn", MAX_BODY);
        if (bodyEn == null) throw ApiException.badRequest("bodyEn must not be empty");
        Instant now = clock.instant();
        var row = new BroadcastEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setAuthorUserId(authorId); row.setAuthorRole(authorRole); row.setKind(kind);
        row.setWeekStart(week(kind, request.weekStart()));
        row.setTitle(SafeText.plainText(request.title(), "title", MAX_TITLE));
        row.setBodyEn(bodyEn); row.setBodyAr(SafeText.plainText(request.bodyAr(), "bodyAr", MAX_BODY));
        if (request.attachment() != null) {
            row.setAttachmentUrl(SafeText.plainText(request.attachment().url(), "attachment.url", 500));
            row.setAttachmentName(SafeText.plainText(request.attachment().name(), "attachment.name", 200));
            if (row.getAttachmentUrl() == null) throw ApiException.badRequest("attachment.url must not be empty");
        }
        row.setAudienceRoles(String.join(",", audience)); row.setCurriculum(curriculum);
        row.setSectionIds(sections == null ? null : sections.stream().map(ClassEntity::getId).collect(Collectors.joining(",")));
        row.setExpiresAt(expiry(request.expiresAt(), now)); row.setCreatedAt(now);
        if (WEEKLY_PLAN.equals(kind)) replacePlan(schoolId, row);
        return rows.save(row);
    }

    /** One plan per week per department: the previous one and its read marks go, so the new one arrives unread. */
    private void replacePlan(String schoolId, BroadcastEntity row) {
        for (var previous : rows.weeklyPlans(schoolId, row.getWeekStart(), row.getCurriculum())) {
            reads.deleteByBroadcast(previous.getId());
            rows.delete(previous);
        }
    }

    /**
     * The dashboard recipients: the teachers assigned to the target sections and the coordinators whose scope meets
     * the row's track, each with her own screen as the notification's link. Parents have no bell — they read the feed.
     */
    private void fanOut(BroadcastEntity row, String schoolId, List<String> audience, List<ClassEntity> targets,
                        List<TeachingAssignmentEntity> assignments) {
        if (audience.contains(TEACHERS)) {
            var ids = targets.stream().map(ClassEntity::getId).collect(Collectors.toSet());
            notify(row, schoolId, assignments.stream().filter(a -> ids.contains(a.getClassId()))
                    .map(TeachingAssignmentEntity::getTeacherId).collect(Collectors.toCollection(LinkedHashSet::new)), "/teacher/broadcasts");
        }
        if (audience.contains(COORDINATORS)) notify(row, schoolId, coordinatorIds(schoolId, tracks(row, targets)), "/coordinator/broadcasts");
    }

    private void notify(BroadcastEntity row, String schoolId, Set<String> recipients, String link) {
        recipients.remove(row.getAuthorUserId());
        for (String userId : recipients)
            notifications.notify(schoolId, userId, NotificationKind.BROADCAST_POSTED, headline(row), row.getBodyEn(), link, null);
    }

    /** The coordinators of this school whose scope names one of these tracks, or names none at all (DR5: both). */
    private Set<String> coordinatorIds(String schoolId, Set<String> tracks) {
        var staff = users.findBySchoolIdAndRole(schoolId, CoordinatorScope.ROLE);
        if (staff.isEmpty()) return new LinkedHashSet<>();
        var out = new LinkedHashSet<String>();
        for (var scope : scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, staff.stream().map(UserEntity::getId).toList()))
            if (scope.getSubject() != null && (blank(scope.getCurriculum()) || tracks.contains(ManagerScope.normalise(scope.getCurriculum()))))
                out.add(scope.getUserId());
        return out;
    }

    // ---------------------------------------------------------------- who sees what

    /**
     * The caller's own reach, resolved once per request rather than per row: her sections and the tracks they are in,
     * and whether her role is an audience at all. `all` is the platform ADMIN reading a school (D6).
     */
    private record Reach(String role, Set<String> sectionIds, Set<String> tracks, boolean all) {
        boolean sees(BroadcastEntity b) {
            if (all) return true;
            var audience = Set.of(b.getAudienceRoles().split(","));
            boolean wanted = switch (role) {
                case "TEACHER" -> audience.contains(TEACHERS);
                case CoordinatorScope.ROLE -> audience.contains(COORDINATORS);
                case ManagerScope.ROLE -> true;                 // she manages the department, whoever the row is for
                default -> false;
            };
            return wanted && touches(b, sectionIds, tracks);
        }
    }

    private Reach reach(Principals.User caller) {
        String role = caller.role() == null ? "" : caller.role();
        if ("ADMIN".equals(role)) return new Reach(role, Set.of(), Set.of(), true);
        if (ManagerScope.ROLE.equals(role)) {
            var sections = managers.sectionsOf(caller);
            return new Reach(role, ids(sections), Set.copyOf(managers.departments(caller)), false);
        }
        if (CoordinatorScope.ROLE.equals(role)) {
            var sections = coordinators.sectionsOf(caller);
            var tracks = coordinators.scopesOf(caller).stream().map(CoordinatorScope.Scope::curriculum).filter(Objects::nonNull)
                    .map(ManagerScope::normalise).collect(Collectors.toSet());
            // A coordinator of both tracks holds a row with no curriculum: every track of her sections is hers.
            return new Reach(role, ids(sections), tracks.isEmpty() ? tracksOf(sections) : tracks, false);
        }
        var mine = teachers.classesOf(caller);
        return new Reach(role, ids(mine), tracksOf(mine), false);
    }

    /** A row reaches a reader when it names one of her sections, or — department-wide — when it names her track. */
    private static boolean touches(BroadcastEntity b, Set<String> sectionIds, Set<String> tracks) {
        if (b.getSectionIds() != null && !b.getSectionIds().isBlank())
            return Arrays.stream(b.getSectionIds().split(",")).anyMatch(sectionIds::contains);
        return b.getCurriculum() != null && tracks.contains(ManagerScope.normalise(b.getCurriculum()));
    }

    /** The parent's rule: the row is for parents, and it names her child's own section or her child's track. */
    private static boolean forChild(BroadcastEntity b, ChildEntity kid) {
        return Set.of(b.getAudienceRoles().split(",")).contains(PARENTS)
                && touches(b, Set.of(kid.getClassId()), Set.of(ManagerScope.normalise(kid.getCurriculum())));
    }

    // ---------------------------------------------------------------- shapes

    private List<BroadcastDto.View> mine(String authorId) {
        var posted = rows.byAuthor(tenant.writeSchoolId(), authorId, PageRequest.of(0, PAGE));
        String name = displayName(authorId);
        return posted.stream().map(b -> view(b, name, true)).toList();
    }

    private BroadcastDto.Feed feed(List<BroadcastEntity> visible, String readerId) {
        if (visible.isEmpty()) return new BroadcastDto.Feed(0, List.of());
        var authors = names(visible.stream().map(BroadcastEntity::getAuthorUserId).distinct().toList());
        var read = Set.copyOf(reads.readBy(readerId, visible.stream().map(BroadcastEntity::getId).toList()));
        var items = visible.stream().map(b -> view(b, authors.getOrDefault(b.getAuthorUserId(), ""), read.contains(b.getId()))).toList();
        return new BroadcastDto.Feed((int) items.stream().filter(v -> !v.read()).count(), items);
    }

    private void mark(BroadcastEntity row, String readerId) {
        if (reads.findOne(row.getId(), readerId).isPresent()) return;
        var mark = new BroadcastReadEntity();
        mark.setId(UUID.randomUUID().toString()); mark.setSchoolId(row.getSchoolId());
        mark.setBroadcastId(row.getId()); mark.setReaderId(readerId); mark.setReadAt(clock.instant());
        reads.save(mark);
    }

    private static BroadcastDto.View view(BroadcastEntity b, String authorName, boolean read) {
        return new BroadcastDto.View(b.getId(), b.getKind(), b.getAuthorUserId(), authorName, b.getAuthorRole(), b.getTitle(),
                b.getBodyEn(), b.getBodyAr(), b.getWeekStart() == null ? null : b.getWeekStart().toString(), b.getCurriculum(),
                b.getSectionIds() == null || b.getSectionIds().isBlank() ? List.of() : List.of(b.getSectionIds().split(",")),
                List.of(b.getAudienceRoles().split(",")),
                b.getAttachmentUrl() == null ? null : new BroadcastDto.Attachment(b.getAttachmentUrl(), b.getAttachmentName()),
                b.getExpiresAt() == null ? null : b.getExpiresAt().toEpochMilli(), b.getCreatedAt().toEpochMilli(), read);
    }

    /** What the bell calls it: the title the author typed, or the kind, because a notification needs one. */
    private static String headline(BroadcastEntity b) {
        if (b.getTitle() != null && !b.getTitle().isBlank()) return b.getTitle();
        return switch (b.getKind()) { case WEEKLY_PLAN -> "Weekly plan"; case EVENT -> "Event"; default -> "Announcement"; };
    }

    // ---------------------------------------------------------------- the words a request may carry

    private static String kind(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case WEEKLY_PLAN -> WEEKLY_PLAN; case ANNOUNCEMENT -> ANNOUNCEMENT; case EVENT -> EVENT;
            default -> throw ApiException.badRequest("kind must be " + WEEKLY_PLAN + ", " + ANNOUNCEMENT + " or " + EVENT + ".");
        };
    }

    /** A non-empty subset of the three audience words, in the contract's order so two equal audiences read alike. */
    private static List<String> audience(List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) throw ApiException.badRequest("audience must name parents, teachers or coordinators.");
        var asked = wanted.stream().filter(Objects::nonNull).map(w -> w.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        var out = List.of(PARENTS, TEACHERS, COORDINATORS).stream().filter(asked::contains).toList();
        if (out.size() != asked.size()) throw ApiException.badRequest("audience must name parents, teachers or coordinators.");
        return out;
    }

    /** The Sunday of the school week a plan is for; required for a plan and refused on the other two kinds. */
    private static LocalDate week(String kind, String raw) {
        if (!WEEKLY_PLAN.equals(kind)) {
            if (raw != null && !raw.isBlank()) throw ApiException.badRequest("weekStart belongs to a " + WEEKLY_PLAN + " only.");
            return null;
        }
        if (raw == null || raw.isBlank()) throw ApiException.badRequest("weekStart is required for a " + WEEKLY_PLAN + " (the Sunday of the week).");
        LocalDate day;
        try { day = LocalDate.parse(raw.trim()); } catch (RuntimeException e) { throw ApiException.badRequest("weekStart must be a date, as 2026-09-27."); }
        return day.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    private static Instant expiry(Long millis, Instant now) {
        if (millis == null) return null;
        var at = Instant.ofEpochMilli(millis);
        if (!at.isAfter(now)) throw ApiException.badRequest("expiresAt must be in the future");
        if (at.isAfter(now.plus(Duration.ofDays(MAX_LIFETIME_DAYS)))) throw ApiException.badRequest("expiresAt must be within " + MAX_LIFETIME_DAYS + " days");
        return at;
    }

    // ---------------------------------------------------------------- small helpers

    private static boolean named(List<String> sectionIds) { return sectionIds != null && !sectionIds.isEmpty(); }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static Set<String> ids(List<ClassEntity> sections) { return sections.stream().map(ClassEntity::getId).collect(Collectors.toSet()); }
    private static Set<String> tracksOf(List<ClassEntity> sections) { return sections.stream().map(k -> ManagerScope.normalise(k.getCurriculum())).collect(Collectors.toSet()); }
    private static Set<String> tracks(BroadcastEntity row, List<ClassEntity> targets) {
        return row.getCurriculum() == null ? tracksOf(targets) : Set.of(ManagerScope.normalise(row.getCurriculum()));
    }

    /** The one track a named list of sections is in, or null when it spans both — the row then names its sections. */
    private static String oneTrack(List<ClassEntity> targets) {
        var found = tracksOf(targets);
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static String one(List<String> departments) {
        if (departments.isEmpty()) throw ApiException.badRequest("You manage no department yet, so there is nobody to tell.");
        if (departments.size() > 1) throw ApiException.badRequest("You manage more than one department: name the sections this is for.");
        return departments.get(0);
    }

    private String displayName(String userId) { return names(List.of(userId)).getOrDefault(userId, ""); }

    private Map<String, String> names(List<String> userIds) {
        var out = new LinkedHashMap<String, String>();
        users.findAllById(userIds).forEach(u -> out.put(u.getId(), quest.server.chat.ChatService.name(u)));
        return out;
    }
}
