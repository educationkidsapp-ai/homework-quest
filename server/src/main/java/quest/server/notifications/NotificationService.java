package quest.server.notifications;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import quest.api.dto.ChatFrame;
import quest.api.dto.NotificationKind;
import quest.api.dto.NotificationView;
import quest.api.dto.UnreadCount;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatBus;
import quest.server.chat.ChatEvent;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.notifications.Entities.NotificationEntity;

/**
 * E2 `backend/notifications` (D26). The write half is {@link #notify} — the row goes in inside the caller's
 * transaction and the frame goes out on the {@link ChatBus} <em>after that transaction commits</em>, so the
 * dashboard never receives a notification it cannot then read back over REST. The read half is the four
 * `/me/notifications` routes, each scoped to the caller's own `userId`.
 *
 * <p><strong>Idempotence.</strong> A row is written on a lesson <em>transition</em>, never on a status that is
 * merely re-asserted: {@link #onLessonTransition} is given the status the row had before the write and does
 * nothing when it is the same. A poll writes no status at all and so can never make a row; a retry that reaches
 * `review` after an `error` is a real transition and does notify, which is the point — she wants to know it
 * finished the second time too.
 *
 * <p>The recipient is the lesson's creator, resolved from `lessons.created_by` (an address) to a `users` row. A
 * lesson created by the seed, or by an address no dashboard user holds, notifies nobody.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    /**
     * Titles and bodies are English server strings; the dashboard localises from `kind` and falls back to these.
     *
     * <p>`BODY_MAX` is public because it is a contract, not an implementation detail: a caller whose text *becomes*
     * a notification body has to refuse a longer one at the edge rather than let {@link #clip} shorten it silently.
     * `TeacherDto.CoordinatorMessageRequest` is validated against this very number.
     */
    public static final int TITLE_MAX = 120, BODY_MAX = 500;
    /** T1: a `chat.message` body is the first 120 characters of what was written — the bell is a cue, not the thread. */
    public static final int CHAT_BODY_MAX = 120;
    private static final int DEFAULT_LIMIT = 20, MAX_LIMIT = 100;

    private final NotificationRepository rows; private final UserRepository users; private final ChatBus bus;
    private final Json json; private final Clock clock; private final NotificationRows upserts;

    public NotificationService(NotificationRepository rows, UserRepository users, ChatBus bus, Json json, Clock clock,
                              NotificationRows upserts) {
        this.rows = rows; this.users = users; this.bus = bus; this.json = json; this.clock = clock; this.upserts = upserts;
    }

    // ---------------------------------------------------------------- writing

    /** Writes one row and publishes it to every socket of {@code userId} once the surrounding transaction commits. */
    @Transactional
    public NotificationView notify(String schoolId, String userId, NotificationKind kind, String title, String body, String link, String lessonId) {
        var e = new NotificationEntity();
        e.setId(UUID.randomUUID().toString()); e.setSchoolId(schoolId); e.setUserId(userId); e.setKind(key(kind));
        e.setTitle(clip(title, TITLE_MAX)); e.setBody(clip(body, BODY_MAX)); e.setLink(link); e.setLessonId(lessonId); e.setCreatedAt(clock.instant());
        rows.save(e);
        var view = view(e);
        publishAfterCommit(schoolId, userId, view);
        return view;
    }

    /**
     * The lesson lifecycle's three moments, from {@link quest.server.analysis.LessonState}: the skills are waiting
     * for her, the questions are written, or the job stopped. `analyzing` and `generating` are steps on the way and
     * notify nobody, and neither does an edit that leaves the status where it was.
     */
    public void onLessonTransition(LessonEntity lesson, String previousStatus) {
        String now = lesson.getStatus();
        if (now == null || now.equals(previousStatus)) return;
        NotificationKind kind = switch (now) {
            case "needs_review" -> NotificationKind.LESSON_NEEDS_SKILLS;
            case "review" -> NotificationKind.LESSON_READY;
            case "error", "paused" -> NotificationKind.LESSON_FAILED;
            default -> null;
        };
        if (kind == null) return;
        String recipient = creatorId(lesson);
        if (recipient == null) return;
        // The three lesson kinds are the only ones `now` can map to above; TEACHER_MESSAGE is written by
        // `TeacherMessageService` and BROADCAST_POSTED by `BroadcastService`, never by a lesson transition, and the
        // switch has to say so.
        String title = switch (kind) {
            case LESSON_NEEDS_SKILLS -> "Skills to confirm";
            case LESSON_READY -> "Questions ready";
            case LESSON_FAILED -> "Generation stopped";
            case TEACHER_MESSAGE, BROADCAST_POSTED, CHAT_MESSAGE -> throw new IllegalStateException(key(kind) + " is not a lesson transition");
        };
        String name = lesson.getTitle() == null || lesson.getTitle().isBlank() ? "Your lesson" : lesson.getTitle().trim();
        String body = switch (kind) {
            case LESSON_NEEDS_SKILLS -> name + " has been analysed. Confirm the skills to start writing the questions.";
            case LESSON_READY -> name + " is ready to review.";
            case LESSON_FAILED -> lesson.getErrorMessage() == null || lesson.getErrorMessage().isBlank() ? name + " stopped before it finished." : lesson.getErrorMessage();
            case TEACHER_MESSAGE, BROADCAST_POSTED, CHAT_MESSAGE -> throw new IllegalStateException(key(kind) + " is not a lesson transition");
        };
        try {
            notify(lesson.getSchoolId(), recipient, kind, title, body, link(roleOf(recipient), lesson.getId()), lesson.getId());
        } catch (RuntimeException e) {
            // never fail the pipeline over the bell: the lesson's own status write is what matters here
            log.warn("notifications: could not write {} for lesson {}: {}", key(kind), lesson.getId(), e.toString());
        }
    }

    /**
     * T1 (owner's list: "no notification when a teacher messages a manager"). One row per **thread** per recipient,
     * not one per message: the recipient's unread `chat.message` row for that thread is updated in place — new body,
     * new time, the same id — and only written fresh when she has none unread. So a conversation of twenty messages is
     * one bell entry that always shows the latest line, and {@link #markThreadRead} clears it when she opens the
     * thread.
     *
     * <p>The row's `lessonId` is the **thread's** id, the field's general meaning ("the row this is about"), which is
     * what both the throttle and the read-clear key on. `link` is the recipient's own Messages screen: a coordinator
     * sent to `/management/messages` reaches a screen she has no route to.
     *
     * <p><strong>One statement, not a read and then an insert</strong> (review): the "one unread row" rule is enforced
     * by V26's unique index and applied by {@link NotificationRows#upsertUnread}, in a transaction of its own, so two
     * messages landing on one thread at the same moment cannot both decide that she has none unread. Never fails the
     * send either way: a bell that could not be written is logged, and the message itself is committed and delivered
     * on the socket exactly as before.
     */
    public void chatMessage(String schoolId, String userId, String threadId, String from, String body) {
        if (userId == null || threadId == null) return;
        String title = clip("Message from " + (from == null || from.isBlank() ? "your school" : from), TITLE_MAX);
        try {
            var row = upserts.upsertUnread(schoolId, userId, key(NotificationKind.CHAT_MESSAGE), threadId,
                    title, clip(body, CHAT_BODY_MAX), messagesLink(roleOf(userId), threadId));
            publishAfterCommit(schoolId, userId, view(row));
        } catch (RuntimeException e) {
            log.warn("notifications: could not write chat.message for thread {}: {}", threadId, e.toString());
        }
    }

    /** T1: she opened the thread, so its bell entry is read too — one statement, whatever put the row there. */
    @Transactional
    public void markThreadRead(String userId, String threadId) {
        if (userId == null || threadId == null) return;
        rows.markAboutRead(userId, key(NotificationKind.CHAT_MESSAGE), threadId, clock.instant());
    }

    /**
     * Withdraws every notification about one entity — what a broadcast's replacement needs (RM2): a weekly plan
     * re-posted for the same week replaces the row, and the bell must not go on offering the superseded title and
     * body, linked to a screen that no longer has it. Nothing is pushed to say so: a bell that lost a row simply has
     * one fewer the next time it is read, and the socket carries the new one.
     */
    @Transactional
    public int forget(String entityId) { return entityId == null ? 0 : rows.deleteByEntity(entityId); }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<NotificationView> list(Principals.User caller, Boolean unread, Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) throw ApiException.badRequest("limit must be 1–" + MAX_LIMIT + ".");
        var page = PageRequest.of(0, n);
        var found = Boolean.TRUE.equals(unread) ? rows.newestUnread(caller.userId(), page) : rows.newest(caller.userId(), page);
        return found.stream().map(NotificationService::view).toList();
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(Principals.User caller) { return new UnreadCount(rows.countUnread(caller.userId())); }

    /** Marks one of the caller's own rows read; another user's id is 404, because the row is not hers to know about. */
    @Transactional
    public NotificationView markRead(Principals.User caller, String id) {
        var e = rows.findOwned(id, caller.userId()).orElseThrow(() -> ApiException.notFound("notification"));
        if (e.getReadAt() == null) { e.setReadAt(clock.instant()); rows.save(e); }
        return view(e);
    }

    @Transactional
    public UnreadCount markAllRead(Principals.User caller) {
        rows.markAllRead(caller.userId(), clock.instant());
        return new UnreadCount(0);
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * After the commit, not before: the socket frame is the dashboard's cue to refetch, and a frame that arrives
     * ahead of the row would show a bell the REST list cannot explain. Outside a transaction (a test, a job that
     * saved on its own) there is nothing to wait for and it goes out at once.
     */
    private void publishAfterCommit(String schoolId, String userId, NotificationView view) {
        String frame = json.encodeShared(new ChatFrame.Notification(view), ChatFrame.Companion.serializer());
        var event = ChatEvent.notification(schoolId, userId, frame);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { bus.publish(event); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { bus.publish(event); }
        });
    }

    /**
     * The dashboard user behind `lessons.created_by`, or null when the address belongs to nobody — the seed writes
     * `seed` there, and a user who has since been removed leaves an address no row holds. The lookup is the native,
     * unfiltered one on purpose: a pipeline thread carries no school scope at all, and it answers an id, not a row.
     */
    private String creatorId(LessonEntity lesson) {
        String email = lesson.getCreatedBy();
        if (email == null || email.isBlank()) return null;
        return users.findIdByEmailAcrossSchools(email).orElse(null);
    }

    /** Which dashboard the link belongs to. Unfiltered (T1), so an ADMIN — who carries no school — is not read as a teacher. */
    private String roleOf(String userId) { return users.findRoleAcrossSchools(userId).orElse("TEACHER"); }

    static String link(String role, String lessonId) { return ("ADMIN".equals(role) ? "/admin/lessons/" : "/teacher/lessons/") + lessonId; }

    /**
     * MG1 (owner's item 7): <strong>every notification row carries a link the recipient's own dashboard can open.</strong>
     * The three areas are the three dashboards a broadcast can land on, and the area is the <em>recipient's</em> role,
     * never the author's — a coordinator sent to `/teacher/broadcasts` reaches a screen she has no route to.
     */
    public static String area(String role) {
        return switch (role == null ? "" : role) {
            case "COORDINATOR" -> "coordinator";
            case "MANAGERIAL" -> "management";
            default -> "teacher";
        };
    }

    /** `broadcast.posted`: the recipient's own feed, opened on the row itself. */
    public static String broadcastLink(String role, String broadcastId) { return "/" + area(role) + "/broadcasts?open=" + broadcastId; }

    /**
     * T1 `chat.message`: the **recipient's own** Messages screen, opened on the thread the message landed in. The four
     * dashboards do not agree on the path — a teacher's inbox is `/teacher/chat`, everyone else's is `…/messages` —
     * and the link has to be the one the recipient's router has, not a pattern.
     */
    public static String messagesLink(String role, String threadId) {
        String screen = switch (role == null ? "" : role) {
            case "ADMIN" -> "/admin/messages";
            case "COORDINATOR" -> "/coordinator/messages";
            case "MANAGERIAL" -> "/management/messages";
            default -> "/teacher/chat";
        };
        return threadId == null || threadId.isBlank() ? screen : screen + "?thread=" + threadId;
    }

    /** `teacher.message`: the manager's Messages screen, on the thread the message was appended to (MG1). */
    public static String threadLink(String threadId) {
        return threadId == null || threadId.isBlank() ? "/management/messages" : "/management/messages?thread=" + threadId;
    }
    static String key(NotificationKind kind) { return kind.name().toLowerCase(Locale.ROOT).replaceFirst("_", "."); }
    static NotificationKind kind(String key) { return NotificationKind.valueOf(key.toUpperCase(Locale.ROOT).replace('.', '_')); }

    private static String clip(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    static NotificationView view(NotificationEntity e) {
        return new NotificationView(e.getId(), kind(e.getKind()), e.getTitle(), e.getBody(), e.getLink(), e.getLessonId(),
                e.getReadAt() == null ? null : e.getReadAt().toEpochMilli(), e.getCreatedAt().toEpochMilli());
    }
}
