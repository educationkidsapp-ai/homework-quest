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
    /** Titles and bodies are English server strings; the dashboard localises from `kind` and falls back to these. */
    static final int TITLE_MAX = 120, BODY_MAX = 500;
    private static final int DEFAULT_LIMIT = 20, MAX_LIMIT = 100;

    private final NotificationRepository rows; private final UserRepository users; private final ChatBus bus; private final Json json; private final Clock clock;

    public NotificationService(NotificationRepository rows, UserRepository users, ChatBus bus, Json json, Clock clock) {
        this.rows = rows; this.users = users; this.bus = bus; this.json = json; this.clock = clock;
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
        // The three lesson kinds are the only ones `now` can map to above; TEACHER_MESSAGE is written
        // by `TeacherMessageService`, never by a lesson transition, and the switch has to say so.
        String title = switch (kind) {
            case LESSON_NEEDS_SKILLS -> "Skills to confirm";
            case LESSON_READY -> "Questions ready";
            case LESSON_FAILED -> "Generation stopped";
            case TEACHER_MESSAGE -> throw new IllegalStateException("teacher.message is not a lesson transition");
        };
        String name = lesson.getTitle() == null || lesson.getTitle().isBlank() ? "Your lesson" : lesson.getTitle().trim();
        String body = switch (kind) {
            case LESSON_NEEDS_SKILLS -> name + " has been analysed. Confirm the skills to start writing the questions.";
            case LESSON_READY -> name + " is ready to review.";
            case LESSON_FAILED -> lesson.getErrorMessage() == null || lesson.getErrorMessage().isBlank() ? name + " stopped before it finished." : lesson.getErrorMessage();
            case TEACHER_MESSAGE -> throw new IllegalStateException("teacher.message is not a lesson transition");
        };
        try {
            notify(lesson.getSchoolId(), recipient, kind, title, body, link(roleOf(recipient), lesson.getId()), lesson.getId());
        } catch (RuntimeException e) {
            // never fail the pipeline over the bell: the lesson's own status write is what matters here
            log.warn("notifications: could not write {} for lesson {}: {}", key(kind), lesson.getId(), e.toString());
        }
    }

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

    /** Which dashboard the link belongs to. An Admin's row is not readable under a school filter; she gets the teacher path then, which is the common case anyway. */
    private String roleOf(String userId) { return users.findById(userId).map(quest.server.auth.Entities.UserEntity::getRole).orElse("TEACHER"); }

    static String link(String role, String lessonId) { return ("ADMIN".equals(role) ? "/admin/lessons/" : "/teacher/lessons/") + lessonId; }
    static String key(NotificationKind kind) { return kind.name().toLowerCase(Locale.ROOT).replaceFirst("_", "."); }
    static NotificationKind kind(String key) { return NotificationKind.valueOf(key.toUpperCase(Locale.ROOT).replace('.', '_')); }

    private static String clip(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    static NotificationView view(NotificationEntity e) {
        return new NotificationView(e.getId(), kind(e.getKind()), e.getTitle(), e.getBody(), e.getLink(), e.getLessonId(),
                e.getReadAt() == null ? null : e.getReadAt().toEpochMilli(), e.getCreatedAt().toEpochMilli());
    }
}
