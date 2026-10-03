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
import quest.server.tenancy.ManagerScope;
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
    /** MG1's archive: the default window is the last twelve weeks, the widest is two years, and one page is 200 plans. */
    private static final int ARCHIVE_WEEKS = 12, ARCHIVE_MAX_WEEKS = 104, ARCHIVE_PAGE = 200;

    private final BroadcastRepository rows; private final BroadcastReadRepository reads;
    private final ManagerScope managers; private final CoordinatorScope coordinators; private final TeacherScope teachers;
    private final UserRepository users; private final ChildService childService;
    private final NotificationService notifications; private final CoordinatorAnnouncementService announcements;
    private final quest.server.files.AttachmentRepository attachments; private final quest.server.files.FileStore files;
    private final TenantContext tenant; private final Clock clock;
    private final quest.server.children.ChildRepository children;

    public BroadcastService(BroadcastRepository rows, BroadcastReadRepository reads, ManagerScope managers,
                           CoordinatorScope coordinators, TeacherScope teachers,
                           UserRepository users, ChildService childService, NotificationService notifications,
                           CoordinatorAnnouncementService announcements,
                           quest.server.files.AttachmentRepository attachments, quest.server.files.FileStore files,
                           TenantContext tenant, Clock clock, quest.server.children.ChildRepository children) {
        this.rows = rows; this.reads = reads; this.managers = managers; this.coordinators = coordinators;
        this.teachers = teachers; this.users = users; this.childService = childService;
        this.notifications = notifications; this.announcements = announcements; this.attachments = attachments;
        this.files = files; this.tenant = tenant; this.clock = clock; this.children = children;
    }

    // ---------------------------------------------------------------- the manager composes (POST /management/broadcasts)

    /**
     * Her department's broadcast. `sectionIds` empty is the whole department, which is what `curriculum` then carries;
     * a manager who holds two departments and names no section is asked to name them, because one row targets one
     * track.
     *
     * <p>MG1 (owner's item 3): `grade` narrows a department-wide row to one grade of it — "the manager adds the weekly
     * plan for all grades" is a plan per grade as well as one for all of them. It is refused beside `sectionIds`,
     * which already say which grade is meant, and a grade she manages no class in is 400 rather than a broadcast
     * nobody is the audience of.
     */
    @Transactional
    public BroadcastDto.View managerPost(Principals.User caller, BroadcastDto.CreateRequest request) {
        String schoolId = tenant.writeSchoolId();
        boolean plan = WEEKLY_PLAN.equals(kind(request.kind()));
        var departments = managers.departments(caller);
        var reach = managers.reach(caller);
        boolean named = named(request.sectionIds());
        var targets = named ? request.sectionIds().stream().distinct().map(id -> managers.requireSection(caller, id)).toList() : reach.sections();
        if (targets.isEmpty()) throw ApiException.badRequest("You manage no class yet, so there is nobody to tell.");
        String curriculum = named ? oneTrack(targets) : one(departments);
        // A weekly plan is a department's week and replaces the one before it, so it has to say which department:
        // a row with none would match — and delete — every department's plan for that week.
        if (plan && curriculum == null)
            throw ApiException.badRequest("A weekly plan belongs to one department: name the sections of a single track.");
        Integer grade = grade(request.grade(), named, targets);
        // MH1 (owner's item 6): a plan is one grade's week as an image. Both are required rather than defaulted,
        // because the two things she could get wrong — the department-wide plan she did not mean and a plan with
        // nothing in it — are exactly what a default would hide.
        if (plan && grade == null)
            throw ApiException.badRequest("A weekly plan is for one grade: name the grade it is for.");
        if (plan && blank(request.attachmentId()))
            throw ApiException.badRequest("A weekly plan is an image or a PDF: upload it to /media/attachments first and send attachmentId.");
        // Her plan goes to the whole grade whatever she ticked: DR6 gives the plan one audience and the owner's list
        // names it — the parents, the teachers and the coordinators of that grade.
        var audience = plan ? List.of(PARENTS, TEACHERS, COORDINATORS) : audience(request.audience());
        var row = write(schoolId, caller.userId(), ManagerScope.ROLE, request, curriculum, grade, null, named ? targets : null, audience);
        fanOut(row, schoolId, audience, reach);
        tellParents(row, schoolId);
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
        if (request.grade() != null) throw ApiException.badRequest("A grade narrows a manager's department; your row names its sections.");
        boolean named = named(request.sectionIds());
        var targets = named ? request.sectionIds().stream().distinct().map(id -> coordinators.requireSection(caller, id)).toList()
                : coordinators.sectionsOf(caller);
        if (targets.isEmpty()) throw ApiException.badRequest("You coordinate no class yet, so there is nobody to tell.");
        var row = write(schoolId, caller.userId(), CoordinatorScope.ROLE, request, null, null, subjectsOf(caller, targets), targets, List.of(PARENTS));
        tellParents(row, schoolId);
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

    /**
     * One row marked read by a dashboard user; a row she is not an audience of is 404, not 403. The school is named as
     * well as filtered — `findOneById` is a query and so is reduced by the `school` filter, but an ADMIN runs with no
     * filter at all when she sends no `X-School-Id`, and a read mark is a write.
     */
    @Transactional
    public BroadcastDto.View staffRead(Principals.User caller, String broadcastId) {
        String schoolId = tenant.writeSchoolId();
        var mine = reach(caller);
        var row = rows.findOneById(broadcastId)
                .filter(b -> b.getSchoolId().equals(schoolId))
                .filter(b -> caller.userId().equals(b.getAuthorUserId()) || mine.sees(b))
                .orElseThrow(() -> ApiException.notFound("broadcast"));
        mark(row, caller.userId());
        return view(row, displayName(row.getAuthorUserId()), true);
    }

    // ---------------------------------------------------------------- MG1: the weekly-plan archive

    /**
     * `GET /management/weekly-plans?from&to&grade` (owner's item 4: "see all weekly plans"). Every weekly plan of her
     * department(s) inside the window, newest week first, each week's plans all-grades first then by grade.
     *
     * <p><strong>Past weeks are the point.</strong> The feeds read {@link BroadcastRepository#live} and drop an expired
     * row; the archive reads {@link BroadcastRepository#plansBetween}, which names no expiry at all, so a plan whose
     * week has gone by is still there. The window defaults to the last twelve weeks and may not span more than two
     * years — a screen, not an export.
     *
     * <p><strong>`readBy` is the grouped count</strong> ({@link BroadcastReadRepository#countsBy}), one statement for
     * the whole page. There is <em>no</em> `audienceSize` beside it: an audience is resolved per reader out of
     * `staff_scopes` and counting one would be a statement per plan — the manager sees how many opened it, and who
     * did not is `GET /management/people`.
     */
    public BroadcastDto.PlanArchive managerArchive(Principals.User caller, String from, String to, Integer grade) {
        String schoolId = tenant.writeSchoolId();
        var mine = reach(ManagerScope.require(caller));
        var window = window(from, to);
        var found = plans(schoolId, window).stream()
                .filter(b -> caller.userId().equals(b.getAuthorUserId()) || mine.sees(b))
                .filter(b -> grade == null || grade.equals(b.getGrade())).toList();
        return archive(window, found, caller.userId(), true);
    }

    /**
     * `GET /me/weekly-plans?from&to` — the plans whose audience includes this teacher, coordinator or manager, by the
     * very predicate {@link #forStaff} filters her feed with, past weeks included.
     */
    public BroadcastDto.PlanArchive myArchive(Principals.User caller, String from, String to) {
        String schoolId = tenant.writeSchoolId();
        var mine = reach(caller);
        var window = window(from, to);
        return archive(window, plans(schoolId, window).stream().filter(mine::sees).toList(), caller.userId(), false);
    }

    /**
     * `GET /children/{id}/weekly-plans?from&to` — the app's archive, named by child for {@link #forChild}'s reason
     * (a flagged parent route has to say which child it is about). A child on no section yet gets an empty window.
     */
    public BroadcastDto.PlanArchive childArchive(Principals.Parent parent, String childId, String from, String to) {
        var kid = childService.owned(childId, parent);
        var window = window(from, to);
        if (kid.getClassId() == null) return new BroadcastDto.PlanArchive(window.from().toString(), window.to().toString(), 0, List.of());
        var found = rows.plansBetween(kid.getSchoolId(), window.from(), window.to(), PageRequest.of(0, ARCHIVE_PAGE)).stream()
                .filter(b -> forChild(b, kid)).toList();
        return archive(window, found, parent.parentId(), false);
    }

    /** The window a request asked for, both ends snapped to the Sunday of their week — plans are stored that way. */
    private record Window(LocalDate from, LocalDate to) {}

    private List<BroadcastEntity> plans(String schoolId, Window window) {
        return rows.plansBetween(schoolId, window.from(), window.to(), PageRequest.of(0, ARCHIVE_PAGE));
    }

    private Window window(String from, String to) {
        LocalDate end = blank(to) ? LocalDate.now(clock) : date(to, "to");
        LocalDate start = blank(from) ? end.minusWeeks(ARCHIVE_WEEKS - 1L) : date(from, "from");
        if (start.isAfter(end)) throw ApiException.badRequest("from must not be after to.");
        if (start.isBefore(end.minusWeeks(ARCHIVE_MAX_WEEKS))) throw ApiException.badRequest("Ask for at most " + ARCHIVE_MAX_WEEKS + " weeks at a time.");
        return new Window(sunday(start), sunday(end));
    }

    /** One page of plans, grouped by week (newest first, as the query ordered them) and sorted inside it by grade. */
    private BroadcastDto.PlanArchive archive(Window window, List<BroadcastEntity> found, String readerId, boolean counts) {
        var authors = names(found.stream().map(BroadcastEntity::getAuthorUserId).distinct().toList());
        var ids = found.stream().map(BroadcastEntity::getId).toList();
        var read = ids.isEmpty() ? Set.<String>of() : Set.copyOf(reads.readBy(readerId, ids));
        var readBy = counts && !ids.isEmpty() ? readCounts(ids) : Map.<String, Integer>of();
        var byWeek = new LinkedHashMap<LocalDate, List<BroadcastDto.PlanEntry>>();
        for (var b : found)
            byWeek.computeIfAbsent(b.getWeekStart(), week -> new ArrayList<>())
                    .add(new BroadcastDto.PlanEntry(view(b, authors.getOrDefault(b.getAuthorUserId(), ""), read.contains(b.getId())),
                            counts ? readBy.getOrDefault(b.getId(), 0) : null));
        int unread = (int) found.stream().filter(b -> !read.contains(b.getId())).count();
        var weeks = new ArrayList<BroadcastDto.PlanWeek>(byWeek.size());
        byWeek.forEach((week, items) -> {
            var sorted = new ArrayList<>(items);
            // All-grades first, then grade 1 upwards: the department's own plan is the heading of its week.
            sorted.sort(java.util.Comparator.comparingInt(e -> e.plan().grade() == null ? -1 : e.plan().grade()));
            weeks.add(new BroadcastDto.PlanWeek(week.toString(), List.copyOf(sorted)));
        });
        return new BroadcastDto.PlanArchive(window.from().toString(), window.to().toString(), unread, List.copyOf(weeks));
    }

    private Map<String, Integer> readCounts(List<String> ids) {
        var out = new LinkedHashMap<String, Integer>();
        for (Object[] row : reads.countsBy(ids)) out.put((String) row[0], ((Number) row[1]).intValue());
        return out;
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
                                  String curriculum, Integer grade, String subject, List<ClassEntity> sections, List<String> audience) {
        String kind = kind(request.kind());
        Instant now = clock.instant();
        var row = new BroadcastEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setAuthorUserId(authorId); row.setAuthorRole(authorRole); row.setKind(kind);
        row.setWeekStart(week(kind, request.weekStart()));
        String title = SafeText.plainText(request.title(), "title", MAX_TITLE);
        String bodyEn = SafeText.plainText(request.bodyEn(), "bodyEn", MAX_BODY);
        if (WEEKLY_PLAN.equals(kind)) {
            // MH1: "no title/message — just the week and an uploaded image". `broadcasts.body_en` is NOT NULL and the
            // bell needs a headline, so the server writes the sentence she did not have to type.
            if (title == null) title = "Weekly plan · Grade " + grade + " · week of " + row.getWeekStart();
            if (bodyEn == null) bodyEn = title;
        }
        if (bodyEn == null) throw ApiException.badRequest("bodyEn must not be empty");
        row.setTitle(title); row.setBodyEn(bodyEn); row.setBodyAr(SafeText.plainText(request.bodyAr(), "bodyAr", MAX_BODY));
        attach(row, schoolId, authorId, request);
        row.setAudienceRoles(String.join(",", audience)); row.setCurriculum(curriculum); row.setGrade(grade); row.setSubject(subject);
        row.setSectionIds(sections == null ? null : sections.stream().map(ClassEntity::getId).collect(Collectors.joining(",")));
        row.setExpiresAt(expiry(request.expiresAt(), now)); row.setCreatedAt(now);
        if (WEEKLY_PLAN.equals(kind)) replacePlan(schoolId, row);
        return rows.save(row);
    }

    /**
     * One plan per week per department <em>and grade</em> (V23): the previous row, its read marks <em>and its bell
     * entries</em> go, so the week has one plan wherever it is read. The notifications are found by the broadcast id every `broadcast.posted`
     * row carries as its entity id — without it a superseded plan would go on offering its title and body from the
     * bell, linked to a feed that has only the new one.
     */
    private void replacePlan(String schoolId, BroadcastEntity row) {
        for (var previous : rows.weeklyPlans(schoolId, row.getWeekStart(), row.getCurriculum(), row.getGrade())) {
            notifications.forget(previous.getId());
            reads.deleteByBroadcast(previous.getId());
            rows.delete(previous);
            rows.flush();                                                       // so the count below cannot see it
            discard(previous.getAttachmentId(), row.getAttachmentId());
        }
    }

    /**
     * The superseded plan's image, reclaimed <strong>at once</strong>: a plan re-posted every week would otherwise
     * leave one orphaned upload per post, readable by nobody but its uploader and swept by nothing — the leak the
     * reviewer found in V24's own comment. {@code UploadRetention.sweep} is the backstop for everything this misses
     * (an expired row deleted elsewhere, a upload nobody ever attached).
     *
     * <p>`keep` is the replacement's own attachment: re-posting the same `attachmentId` is allowed, and deleting it
     * here would take the bytes out from under the row about to be saved. The count is the second guard — two
     * broadcasts may point at one image, and the last one out deletes it.
     */
    private void discard(String attachmentId, String keep) {
        if (attachmentId == null || attachmentId.equals(keep)) return;
        if (rows.countByAttachment(attachmentId) > 0) return;
        attachments.findOneById(attachmentId).ifPresent(file -> {
            files.delete(file.getStoragePath());
            attachments.delete(file);
        });
    }

    /**
     * The dashboard recipients — <strong>decided by the very predicate the feeds use</strong> ({@link #reach} and
     * {@link Reach#sees}), never by a second rule beside it. The bell and `GET /me/broadcasts` have to agree: a
     * notification carries the row's title and body, so a recipient the feed omits would be told what was said and
     * then sent to a screen that does not have it. That is what a track-only test for coordinators did to a row
     * naming sections of a department they coordinate nothing in.
     *
     * <p>It costs one reach per candidate — a handful of statements for a handful of staff, and only on a post,
     * which is a person pressing a button. The candidates are the department's teachers and the school's
     * coordinators; `sees` decides the rest, audience word included.
     */
    private void fanOut(BroadcastEntity row, String schoolId, List<String> audience, CoordinatorScope.Reach reach) {
        if (audience.contains(TEACHERS))
            notify(row, schoolId, reached(row, ManagerScope.teacherIds(reach), "TEACHER", schoolId), "TEACHER");
        if (audience.contains(COORDINATORS))
            notify(row, schoolId, reached(row, users.findBySchoolIdAndRole(schoolId, CoordinatorScope.ROLE).stream().map(UserEntity::getId).toList(),
                    CoordinatorScope.ROLE, schoolId), CoordinatorScope.ROLE);
    }

    /** Those of these people whose own feed would show this row; the author is never told about her own post. */
    private Set<String> reached(BroadcastEntity row, List<String> userIds, String role, String schoolId) {
        var out = new LinkedHashSet<String>();
        for (String userId : userIds)
            if (!userId.equals(row.getAuthorUserId()) && reach(new Principals.User(userId, "", role, schoolId)).sees(row)) out.add(userId);
        return out;
    }

    /**
     * MG1 (owner's item 7): the link opens the row itself on the recipient's <em>own</em> area —
     * `/teacher/broadcasts?open=…` for a teacher, `/coordinator/…` for a coordinator, `/management/…` for a manager.
     * A bell that sent a coordinator to the teacher area was a dead end for half the recipients of every post.
     */
    private void notify(BroadcastEntity row, String schoolId, Set<String> recipients, String role) {
        String link = NotificationService.broadcastLink(role, row.getId());
        for (String userId : recipients)
            notifications.notify(schoolId, userId, NotificationKind.BROADCAST_POSTED, headline(row), row.getBodyEn(), link, row.getId());
    }

    /**
     * B4: every parent whose child's feed now shows this row is told — a `/me/notifications` row and a push, once per
     * parent — decided by {@link #forChild}, the very predicate `GET /children/{id}/broadcasts` filters with, so she is
     * never told about a row her feed does not have. One read of the post's own school's children, on a post.
     */
    private void tellParents(BroadcastEntity row, String schoolId) {
        if (!Set.of(row.getAudienceRoles().split(",")).contains(PARENTS)) return;
        var reached = children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(schoolId).stream()
                .filter(kid -> kid.getClassId() != null && kid.isActive() && forChild(row, kid)).toList();
        notifications.parentsOfBroadcast(schoolId, reached, row.getId(), headline(row), row.getBodyEn(), headlineAr(row), row.getBodyAr());
    }

    /** {@link #headline} for an Arabic phone: the title she typed, in whatever language she typed it, or the kind in Arabic. */
    private static String headlineAr(BroadcastEntity b) {
        if (b.getTitle() != null && !b.getTitle().isBlank()) return b.getTitle();
        return switch (b.getKind()) { case WEEKLY_PLAN -> "الخطة الأسبوعية"; case EVENT -> "فعالية"; default -> "إعلان"; };
    }

    // ---------------------------------------------------------------- MH1: the attachment

    /**
     * What is attached, resolved to an `attachments` row when the request names one. <strong>Only the author's own
     * upload</strong>: a composer could otherwise name any id and publish another school's — or another manager's —
     * image to her whole department, and a 400 here is the same answer an id that never existed gets.
     *
     * <p>`attachmentUrl`/`attachmentName` are written beside the id so a feed row needs no second read, and the free
     * text V22 allowed still works for a caller that sends `attachment` without an id — the rows QA already has.
     */
    private void attach(BroadcastEntity row, String schoolId, String authorId, BroadcastDto.CreateRequest request) {
        String wanted = request.attachmentId();
        if (blank(wanted) && request.attachment() != null) wanted = request.attachment().id();
        if (!blank(wanted)) {
            String id = wanted.trim();
            var file = attachments.findOneById(id)
                    .filter(a -> schoolId.equals(a.getSchoolId()) && authorId.equals(a.getUploadedBy()))
                    .orElseThrow(() -> ApiException.badRequest("Upload the image to /media/attachments first — that attachmentId is not one of yours."));
            // S1: a PDF is a weekly plan's alone — an announcement's attachment is drawn as an image by both clients.
            if (quest.server.files.AttachmentService.PDF.equals(file.getMimeType()) && !WEEKLY_PLAN.equals(row.getKind()))
                throw ApiException.badRequest("Only a weekly plan can carry a PDF; attach an image here.");
            row.setAttachmentId(file.getId()); row.setAttachmentUrl("/media/attachments/" + file.getId());
            row.setAttachmentName(file.getName()); row.setAttachmentType(file.getMimeType());
            return;
        }
        if (request.attachment() == null) return;
        row.setAttachmentUrl(SafeText.plainText(request.attachment().url(), "attachment.url", 500));
        row.setAttachmentName(SafeText.plainText(request.attachment().name(), "attachment.name", 200));
        if (row.getAttachmentUrl() == null) throw ApiException.badRequest("attachment.url must not be empty");
    }

    private static BroadcastDto.Attachment attachment(BroadcastEntity b) {
        if (b.getAttachmentId() == null && b.getAttachmentUrl() == null) return null;
        return new BroadcastDto.Attachment(b.getAttachmentUrl(), b.getAttachmentName(), b.getAttachmentId(), b.getAttachmentType());
    }

    /**
     * MH1: may this caller read those bytes? `GET /media/attachments/{id}` asks it, and it is answered by
     * <strong>the very predicate the feeds use</strong> — an attachment is readable exactly when a broadcast carrying
     * it is, so a plan's image can never be visible to somebody the plan is not, nor hidden from somebody it is.
     * The uploader's own file is {@link quest.server.files.MediaAccess}'s business, not this method's.
     *
     * @param kids the parent's children, empty for a dashboard caller
     */
    public boolean readsAttachment(String attachmentId, String schoolId, Principals.User user, List<ChildEntity> kids) {
        var carrying = rows.byAttachment(schoolId, attachmentId);
        if (carrying.isEmpty()) return false;
        if (user != null) {
            var mine = reach(user);
            return carrying.stream().anyMatch(b -> user.userId().equals(b.getAuthorUserId()) || mine.sees(b));
        }
        return carrying.stream().anyMatch(b -> kids.stream()
                .anyMatch(k -> k.getClassId() != null && schoolId.equals(k.getSchoolId()) && forChild(b, k)));
    }

    // ---------------------------------------------------------------- who sees what

    /**
     * The caller's own reach, resolved once per request rather than per row: her sections and the tracks they are in,
     * and whether her role is an audience at all. `all` is the platform ADMIN reading a school (D6).
     */
    private record Reach(String role, Set<String> sectionIds, Set<String> tracks, Set<String> cells, boolean all) {
        boolean sees(BroadcastEntity b) {
            if (all) return true;
            var audience = Set.of(b.getAudienceRoles().split(","));
            boolean wanted = switch (role) {
                case "TEACHER" -> audience.contains(TEACHERS);
                case CoordinatorScope.ROLE -> audience.contains(COORDINATORS);
                case ManagerScope.ROLE -> true;                 // she manages the department, whoever the row is for
                default -> false;
            };
            return wanted && touches(b, sectionIds, tracks, cells);
        }
    }

    private Reach reach(Principals.User caller) {
        String role = caller.role() == null ? "" : caller.role();
        if ("ADMIN".equals(role)) return new Reach(role, Set.of(), Set.of(), Set.of(), true);
        if (ManagerScope.ROLE.equals(role)) {
            var sections = managers.sectionsOf(caller);
            return new Reach(role, ids(sections), Set.copyOf(managers.departments(caller)), cellsOf(sections), false);
        }
        if (CoordinatorScope.ROLE.equals(role)) {
            var sections = coordinators.sectionsOf(caller);
            var tracks = coordinators.scopesOf(caller).stream().map(CoordinatorScope.Scope::curriculum).filter(Objects::nonNull)
                    .map(ManagerScope::normalise).collect(Collectors.toSet());
            // A coordinator of both tracks holds a row with no curriculum: every track of her sections is hers.
            return new Reach(role, ids(sections), tracks.isEmpty() ? tracksOf(sections) : tracks, cellsOf(sections), false);
        }
        var mine = teachers.classesOf(caller);
        return new Reach(role, ids(mine), tracksOf(mine), cellsOf(mine), false);
    }

    /**
     * A row reaches a reader when it names one of her sections, or — department-wide — when it names her track and,
     * since V23, a grade she is actually in <em>that</em> track. <strong>One predicate, one place</strong>: the feeds,
     * the fan-out that decides who is told, and MG1's three archives all ask this question, so a grade plan can never
     * ring a bell it does not fill.
     *
     * <p><strong>The track and the grade are one key, not two.</strong> A teacher who holds grade 1 British and grade
     * 5 American has tracks {british, american} and grades {1, 5}; matched independently that is four cells and she
     * would be handed the British department's grade 5 plan — a week's plan for children she has never taught, on the
     * feed and in the bell. So {@link Reach#cells} is the set of `curriculum|grade` pairs her sections actually sit
     * in, and a row with a grade is matched against exactly that. {@code tracks} stays for the grade-less row, which
     * is the whole department and asks nothing about a grade.
     */
    private static boolean touches(BroadcastEntity b, Set<String> sectionIds, Set<String> tracks, Set<String> cells) {
        if (b.getSectionIds() != null && !b.getSectionIds().isBlank())
            return Arrays.stream(b.getSectionIds().split(",")).anyMatch(sectionIds::contains);
        if (b.getCurriculum() == null) return false;
        String track = ManagerScope.normalise(b.getCurriculum());
        if (b.getGrade() == null) return tracks.contains(track);
        return cells.contains(cell(track, b.getGrade()));
    }

    /** The parent's rule: the row is for parents, and it names her child's own section, or her track and grade. */
    private static boolean forChild(BroadcastEntity b, ChildEntity kid) {
        String track = ManagerScope.normalise(kid.getCurriculum());
        return Set.of(b.getAudienceRoles().split(",")).contains(PARENTS)
                && touches(b, Set.of(kid.getClassId()), Set.of(track), Set.of(cell(track, kid.getGrade())));
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
                b.getBodyEn(), b.getBodyAr(), b.getWeekStart() == null ? null : b.getWeekStart().toString(), b.getCurriculum(), b.getGrade(), b.getSubject(),
                b.getSectionIds() == null || b.getSectionIds().isBlank() ? List.of() : List.of(b.getSectionIds().split(",")),
                List.of(b.getAudienceRoles().split(",")),
                attachment(b),
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
        return sunday(day);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static LocalDate sunday(LocalDate day) { return day.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)); }

    private static LocalDate date(String raw, String field) {
        try { return LocalDate.parse(raw.trim()); } catch (RuntimeException e) { throw ApiException.badRequest(field + " must be a date, as 2026-09-27."); }
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
    private static Set<String> ids(List<ClassEntity> sections) { return sections.stream().map(ClassEntity::getId).collect(Collectors.toSet()); }
    private static Set<String> tracksOf(List<ClassEntity> sections) { return sections.stream().map(k -> ManagerScope.normalise(k.getCurriculum())).collect(Collectors.toSet()); }
    /** The `curriculum|grade` cells a set of sections sits in — one key, so a track and a grade can never be crossed. */
    private static Set<String> cellsOf(List<ClassEntity> sections) {
        return sections.stream().map(k -> cell(ManagerScope.normalise(k.getCurriculum()), k.getGrade())).collect(Collectors.toSet());
    }
    private static String cell(String track, int grade) { return track + "|" + grade; }

    /**
     * The one grade a row is for, or null for every grade of the department. It is refused beside named sections —
     * those already say which grade is meant, and two ways of saying it is two ways for them to disagree — and a
     * grade the author manages no section in is refused rather than stored as a broadcast with no audience.
     */
    private static Integer grade(Integer wanted, boolean named, List<ClassEntity> reach) {
        if (wanted == null) return null;
        if (named) throw ApiException.badRequest("Name the sections or the grade, not both: the sections already say which grade is meant.");
        if (reach.stream().noneMatch(k -> k.getGrade() == wanted.intValue()))
            throw ApiException.badRequest("You manage no class in grade " + wanted + ".");
        return wanted;
    }
    /**
     * The subjects of the coordinator's own scope that reach these sections — what the app labels her card with ("from
     * your maths coordinator"). Her scope rows, not the sections' subjects: she coordinates maths in a section where
     * english is also taught, and it is her subject the parent is being written to about.
     */
    private String subjectsOf(Principals.User caller, List<ClassEntity> targets) {
        var tracks = tracksOf(targets);
        var subjects = coordinators.scopesOf(caller).stream()
                .filter(s -> s.curriculum() == null || tracks.contains(ManagerScope.normalise(s.curriculum())))
                .map(CoordinatorScope.Scope::subject).filter(Objects::nonNull).distinct().sorted().toList();
        return subjects.isEmpty() ? null : String.join(", ", subjects);
    }

    /**
     * The one track a named list of sections is in, or null when it spans both — the row then names its sections, and
     * a `weekly_plan` that lands here is refused rather than stored without a department.
     */
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
