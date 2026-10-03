package quest.server.notifications;

import java.time.Clock;
import java.util.ArrayList;
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
import quest.api.dto.PushMessage;
import quest.api.dto.UnreadCount;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatBus;
import quest.server.chat.ChatEvent;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.notifications.Entities.NotificationEntity;
import quest.server.push.ParentPush;

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

    /** B3 (V30): a parent's rows carry `parent:<parentId>` as the recipient, an id no dashboard user can have. */
    public static final String PARENT_PREFIX = "parent:";

    private final NotificationRepository rows; private final UserRepository users; private final ChatBus bus;
    private final Json json; private final Clock clock; private final NotificationRows upserts;
    private final quest.server.children.ChildRepository children; private final org.springframework.transaction.support.TransactionTemplate own;
    private final ParentPush push; private final quest.server.exams.ExamSettingsRepository exams;
    private final quest.server.platform.SchoolCalendar calendar;

    public NotificationService(NotificationRepository rows, UserRepository users, ChatBus bus, Json json, Clock clock,
                              NotificationRows upserts, quest.server.children.ChildRepository children,
                              org.springframework.transaction.PlatformTransactionManager transactions, ParentPush push,
                              quest.server.exams.ExamSettingsRepository exams, quest.server.platform.SchoolCalendar calendar) {
        this.rows = rows; this.users = users; this.bus = bus; this.json = json; this.clock = clock; this.upserts = upserts;
        this.children = children; this.push = push; this.exams = exams; this.calendar = calendar;
        this.own = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.own.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The recipient key of a parent's rows. */
    public static String parentRecipient(String parentId) { return PARENT_PREFIX + parentId; }

    /** Whose bell a request reads: the dashboard user's, or — B3 — the signed-in parent's. */
    public static String recipient(Principals.User user, Principals.Parent parent) {
        if (user != null) return user.userId();
        if (parent != null) return parentRecipient(parent.parentId());
        throw ApiException.forbidden("Sign in to read notifications.");
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
            case TEACHER_MESSAGE, BROADCAST_POSTED, CHAT_MESSAGE, EXAM_RELEASED, HOMEWORK_PUBLISHED, EXAM_PUBLISHED, ANNOUNCEMENT_POSTED, QUESTION_SENT, COMPLAINT_STATUS -> throw new IllegalStateException(key(kind) + " is not a lesson transition");
        };
        String name = lesson.getTitle() == null || lesson.getTitle().isBlank() ? "Your lesson" : lesson.getTitle().trim();
        String body = switch (kind) {
            case LESSON_NEEDS_SKILLS -> name + " has been analysed. Confirm the skills to start writing the questions.";
            case LESSON_READY -> name + " is ready to review.";
            case LESSON_FAILED -> lesson.getErrorMessage() == null || lesson.getErrorMessage().isBlank() ? name + " stopped before it finished." : lesson.getErrorMessage();
            case TEACHER_MESSAGE, BROADCAST_POSTED, CHAT_MESSAGE, EXAM_RELEASED, HOMEWORK_PUBLISHED, EXAM_PUBLISHED, ANNOUNCEMENT_POSTED, QUESTION_SENT, COMPLAINT_STATUS -> throw new IllegalStateException(key(kind) + " is not a lesson transition");
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
                    title, clip(body, CHAT_BODY_MAX), messagesLink(roleOf(userId), threadId), null).row();
            publishAfterCommit(schoolId, userId, view(row));
        } catch (RuntimeException e) {
            log.warn("notifications: could not write chat.message for thread {}: {}", threadId, e.toString());
        }
    }

    /**
     * B3 (D5): a staff member wrote to a parent — T1's throttle and read-clear, on the parent's own rows. The row names
     * the child, and `link` is the app's path to the thread (`/children/{childId}/chat/{staffId}`).
     *
     * <p>B4: the throttle is the push's too — a <em>fresh</em> unread row is pushed, a refreshed one is not, so a teacher
     * writing five lines to a parent who has not opened the thread is one push, and the next one comes after she reads it.
     */
    public void parentChatMessage(String schoolId, String parentId, String childId, String threadId, String staffId, String from, String body) {
        if (parentId == null || threadId == null) return;
        boolean named = from != null && !from.isBlank();
        String title = clip("Message from " + (named ? from : "your school"), TITLE_MAX);
        try {
            var upsert = upserts.upsertUnread(schoolId, parentRecipient(parentId), key(NotificationKind.CHAT_MESSAGE), threadId,
                    title, clip(body, CHAT_BODY_MAX), "/children/" + childId + "/chat/" + staffId, childId);
            var row = upsert.row();
            publishAfterCommit(schoolId, row.getUserId(), view(row));
            // What she wrote is in her own words: the Arabic push changes the title only.
            if (upsert.fresh()) push.toParents(List.of(delivery(parentId, row,
                    new Note(NotificationKind.CHAT_MESSAGE, threadId, row.getTitle(), row.getBody(), row.getLink(), "chat:" + threadId)
                            .arabic(named ? "رسالة من " + from : "رسالة من المدرسة", row.getBody()))));
        } catch (RuntimeException e) {
            log.warn("notifications: could not write a parent's chat.message for thread {}: {}", threadId, e.toString());
        }
    }

    /**
     * B3 (D5): one row for the parent of every child on the lesson's section — `exam.released` when an exam's results
     * are released (by the teacher or by the close-of-window sweep), `homework.published` when a homework goes out and,
     * B4, `exam.published` when an exam is published (its title and window, never its content).
     *
     * <p><strong>After the release commits, in a transaction of its own</strong> ({@link #afterCommit}): nothing here can
     * roll the release or the publish back, and a release that did roll back tells nobody. <strong>Once per lesson and
     * child</strong>, decided by V31's unique `once_key` rather than by a read — a release withdrawn and given again, a
     * re-publish, or two instances sweeping the same exam write nothing new.
     */
    public void parentsOf(LessonEntity lesson, NotificationKind kind) {
        if (lesson == null || lesson.getClassId() == null) return;
        String lessonId = lesson.getId(), classId = lesson.getClassId(), schoolId = lesson.getSchoolId();
        boolean titled = lesson.getTitle() != null && !lesson.getTitle().isBlank();
        String name = titled ? lesson.getTitle().trim() : "the lesson", nameAr = titled ? lesson.getTitle().trim() : "الدرس";
        String day = String.valueOf(lesson.getDate());
        afterCommit(lessonId + " (" + key(kind) + ")", () -> {
            var window = kind == NotificationKind.EXAM_PUBLISHED ? exams.findOneByLessonId(lessonId).orElse(null) : null;
            var zone = window == null ? null : calendar.of(schoolId).zone();
            var told = new ArrayList<ParentPush.Delivery>();
            for (var child : children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(classId))
                add(told, tell(schoolId, child.getParentId(), child.getId(), lessonNote(kind, lessonId, child, name, nameAr, day, window, zone), true));
            push.toParents(told);
        });
    }

    private static Note lessonNote(NotificationKind kind, String lessonId, quest.server.children.Entities.ChildEntity child, String name, String nameAr,
                                   String day, quest.server.exams.Entities.ExamSettingsEntity window, java.time.ZoneId zone) {
        String c = child.getId(), who = child.getName(), collapse = "lesson:" + lessonId;
        return switch (kind) {
            case EXAM_RELEASED -> new Note(kind, lessonId, "Results ready: " + name, who + "'s result for " + name + " is ready.",
                    "/children/" + c + "/progress", collapse).arabic("النتائج جاهزة: " + nameAr, "نتيجة " + who + " في " + nameAr + " جاهزة.");
            case HOMEWORK_PUBLISHED -> new Note(kind, lessonId, "New homework: " + name, who + " has new homework for " + day + ".",
                    "/children/" + c + "/map", collapse).arabic("واجب جديد: " + nameAr, "لدى " + who + " واجب جديد ليوم " + day + ".");
            case EXAM_PUBLISHED -> {
                var exam = new Note(kind, lessonId, "New exam: " + name, who + " has an exam: " + name + windowText(window, zone, false) + ".",
                        "/children/" + c + "/map", collapse).arabic("اختبار جديد: " + nameAr, "لدى " + who + " اختبار: " + nameAr + windowText(window, zone, true) + ".");
                yield window == null ? exam : exam.window(window.getOpensAt().toEpochMilli(), window.getClosesAt().toEpochMilli());
            }
            default -> throw new IllegalStateException(key(kind) + " is not about a lesson");
        };
    }

    /** ", open Sun 5 Oct 08:00 – Sun 5 Oct 09:00" in the school's own zone — the push also carries the instants. */
    private static String windowText(quest.server.exams.Entities.ExamSettingsEntity window, java.time.ZoneId zone, boolean arabic) {
        if (window == null) return "";
        var f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", arabic ? Locale.forLanguageTag("ar") : Locale.ENGLISH).withZone(zone);
        return arabic ? "، من " + f.format(window.getOpensAt()) + " إلى " + f.format(window.getClosesAt())
                : ", open " + f.format(window.getOpensAt()) + " – " + f.format(window.getClosesAt());
    }

    /**
     * B4: a manager's or a coordinator's broadcast — weekly plan, announcement or event — reached these children's feed
     * (the caller decided who, with the feed's own predicate). One row per <em>parent</em>, about her first child here:
     * two children in one grade are one plan to read, not two. Without an Arabic body the Arabic push carries the English.
     */
    public void parentsOfBroadcast(String schoolId, List<quest.server.children.Entities.ChildEntity> kids, String broadcastId,
                                   String title, String bodyEn, String titleAr, String bodyAr) {
        var once = onePerParent(kids);
        if (once.isEmpty()) return;
        afterCommit(broadcastId + " (broadcast.posted)", () -> {
            var told = new ArrayList<ParentPush.Delivery>();
            for (var kid : once)
                add(told, tell(schoolId, kid.getParentId(), kid.getId(), new Note(NotificationKind.BROADCAST_POSTED, broadcastId, title, cue(bodyEn),
                        "/children/" + kid.getId() + "/broadcasts?open=" + broadcastId, "broadcast:" + broadcastId)
                        .arabic(titleAr, cue(bodyAr == null || bodyAr.isBlank() ? bodyEn : bodyAr)).broadcast(), true));
            push.toParents(told);
        });
    }

    /** B4: a teacher's note to the parents of a class (§6 screen 16) reached these children. Once per parent. */
    public void parentsOfAnnouncement(String schoolId, List<quest.server.children.Entities.ChildEntity> kids, String announcementId,
                                      String author, String bodyEn, String bodyAr) {
        var once = onePerParent(kids);
        if (once.isEmpty()) return;
        boolean named = author != null && !author.isBlank();
        afterCommit(announcementId + " (announcement.posted)", () -> {
            var told = new ArrayList<ParentPush.Delivery>();
            for (var kid : once)
                add(told, tell(schoolId, kid.getParentId(), kid.getId(), new Note(NotificationKind.ANNOUNCEMENT_POSTED, announcementId,
                        "Note from " + (named ? author : "your child's teacher"), cue(bodyEn),
                        "/children/" + kid.getId() + "/announcements?open=" + announcementId, "announcement:" + announcementId)
                        .arabic(named ? "ملاحظة من " + author : "ملاحظة من المعلمة", cue(bodyAr == null || bodyAr.isBlank() ? bodyEn : bodyAr)), true));
            push.toParents(told);
        });
    }

    /** B4: a teacher sent these children a question to answer in the app (§6 screen 14). Once per question and child. */
    public void parentsOfQuestion(String schoolId, List<quest.server.children.Entities.ChildEntity> kids, String questionId, String author, String title) {
        if (kids.isEmpty()) return;
        boolean named = author != null && !author.isBlank(), titled = title != null && !title.isBlank();
        String what = titled ? title.trim() : "a question", whatAr = titled ? title.trim() : "سؤال";
        afterCommit(questionId + " (question.sent)", () -> {
            var told = new ArrayList<ParentPush.Delivery>();
            for (var kid : kids)
                add(told, tell(schoolId, kid.getParentId(), kid.getId(), new Note(NotificationKind.QUESTION_SENT, questionId,
                        "New question from " + (named ? author : "the teacher"), kid.getName() + " has a question to answer: " + what + ".",
                        "/children/" + kid.getId() + "/teacher-questions/" + questionId, "question:" + questionId)
                        .arabic(named ? "سؤال جديد من " + author : "سؤال جديد من المعلمة", "لدى " + kid.getName() + " سؤال للإجابة عنه: " + whatAr + "."), true));
            push.toParents(told);
        });
    }

    /**
     * B4: a coordinator or a manager resolved one of her threads, or opened it again. One row per change — a complaint
     * reopened and resolved twice is news each time — collapsed with the thread's messages on her phone.
     */
    public void complaintStatus(String schoolId, String parentId, String childId, String threadId, String staffId, boolean complaint, boolean resolved, String by) {
        if (parentId == null || childId == null) return;
        boolean named = by != null && !by.isBlank();
        String what = complaint ? "Complaint" : "Conversation", whatAr = complaint ? "الشكوى" : "المحادثة";
        String title = what + (resolved ? " resolved" : " reopened"), titleAr = resolved ? "تم حل " + whatAr : "أعيد فتح " + whatAr;
        String body = (named ? by : "Your school") + (resolved ? " marked it resolved." : " opened it again.");
        String bodyAr = resolved ? (named ? "أغلقها " + by + " بعد حلها." : "أغلقتها المدرسة بعد حلها.")
                : (named ? "أعاد " + by + " فتحها." : "أعادت المدرسة فتحها.");
        afterCommit(threadId + " (complaint.status)", () -> {
            var told = tell(schoolId, parentId, childId, new Note(NotificationKind.COMPLAINT_STATUS, threadId, title, body,
                    "/children/" + childId + "/chat/" + staffId, "chat:" + threadId).arabic(titleAr, bodyAr), false);
            if (told != null) push.toParents(List.of(told));
        });
    }

    /**
     * B4: what one parent row says, and what its push adds — the Arabic title and body (every kind has both; a staff
     * member's own words stay as written), the broadcast id, an exam's window. The row itself is English, as every row
     * is; the push is the phone's language.
     */
    record Note(NotificationKind kind, String entityId, String title, String body, String link, String collapseKey,
                String titleAr, String bodyAr, boolean isBroadcast, Long opensAt, Long closesAt) {
        Note(NotificationKind kind, String entityId, String title, String body, String link, String collapseKey) {
            this(kind, entityId, title, body, link, collapseKey, null, null, false, null, null);
        }
        Note arabic(String t, String b) { return new Note(kind, entityId, title, body, link, collapseKey, t, b, isBroadcast, opensAt, closesAt); }
        Note broadcast() { return new Note(kind, entityId, title, body, link, collapseKey, titleAr, bodyAr, true, opensAt, closesAt); }
        Note window(long opens, long closes) { return new Note(kind, entityId, title, body, link, collapseKey, titleAr, bodyAr, isBroadcast, opens, closes); }
    }

    /**
     * Writes one parent row — once per `recipient|kind|entity|child` when {@code once} — and, when it was written, sends
     * her the `notification` frame and answers her push, which the fan-out hands to {@link ParentPush} with all the others
     * at once. Runs inside {@link #afterCommit}'s own transaction. Null when she was told already.
     */
    private ParentPush.Delivery tell(String schoolId, String parentId, String childId, Note note, boolean once) {
        if (parentId == null) return null;
        var e = new NotificationEntity();
        e.setId(UUID.randomUUID().toString()); e.setSchoolId(schoolId); e.setUserId(parentRecipient(parentId));
        e.setKind(key(note.kind())); e.setLessonId(note.entityId()); e.setChildId(childId); e.setCreatedAt(clock.instant());
        e.setTitle(clip(note.title(), TITLE_MAX)); e.setBody(clip(note.body(), BODY_MAX)); e.setLink(note.link());
        if (once) e.setOnceKey(e.getUserId() + "|" + e.getKind() + "|" + note.entityId() + "|" + childId);
        if (rows.insertOnce(e) != 1) return null;
        publishAfterCommit(schoolId, e.getUserId(), view(e));
        return delivery(parentId, e, note);
    }

    /** The row as a push, in English and in Arabic. */
    private static ParentPush.Delivery delivery(String parentId, NotificationEntity row, Note note) {
        String broadcastId = note.isBroadcast() ? note.entityId() : null;
        var english = new PushMessage(note.kind(), row.getTitle(), row.getBody(), row.getId(), row.getChildId(), row.getLink(), broadcastId,
                note.collapseKey(), note.opensAt(), note.closesAt());
        var arabic = note.titleAr() == null ? null : new PushMessage(note.kind(), clip(note.titleAr(), TITLE_MAX),
                note.bodyAr() == null ? row.getBody() : clip(note.bodyAr(), BODY_MAX), row.getId(), row.getChildId(), row.getLink(), broadcastId,
                note.collapseKey(), note.opensAt(), note.closesAt());
        return new ParentPush.Delivery(parentId, english, arabic);
    }

    private static void add(List<ParentPush.Delivery> told, ParentPush.Delivery one) { if (one != null) told.add(one); }

    /** The children whose parents are told once each — her first child here — skipping a child with no parent. */
    private static List<quest.server.children.Entities.ChildEntity> onePerParent(List<quest.server.children.Entities.ChildEntity> kids) {
        var seen = new java.util.HashSet<String>();
        return kids.stream().filter(k -> k.getParentId() != null && seen.add(k.getParentId())).toList();
    }

    /** A row is a cue, not the post: the first lines of what was written, as a `chat.message` body is. */
    private static String cue(String body) { return clip(body, CHAT_BODY_MAX * 2); }

    /**
     * After the caller's transaction commits, in one of its own: a parent row is never written for work that rolled
     * back, and nothing here can undo the release, the post or the message that caused it — a failure is logged.
     */
    private void afterCommit(String what, Runnable work) {
        Runnable run = () -> {
            try { own.executeWithoutResult(status -> work.run()); }
            catch (RuntimeException e) { log.warn("notifications: could not tell the parents about {}: {}", what, e.toString()); }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { run.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { run.run(); }
        });
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
    public List<NotificationView> list(String recipient, Boolean unread, Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) throw ApiException.badRequest("limit must be 1–" + MAX_LIMIT + ".");
        var page = PageRequest.of(0, n);
        var found = Boolean.TRUE.equals(unread) ? rows.newestUnread(recipient, page) : rows.newest(recipient, page);
        return found.stream().map(NotificationService::view).toList();
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(String recipient) { return new UnreadCount(rows.countUnread(recipient)); }

    /** Marks one of the caller's own rows read; another user's id is 404, because the row is not hers to know about. */
    @Transactional
    public NotificationView markRead(String recipient, String id) {
        var e = rows.findOwned(id, recipient).orElseThrow(() -> ApiException.notFound("notification"));
        if (e.getReadAt() == null) { e.setReadAt(clock.instant()); rows.save(e); }
        return view(e);
    }

    @Transactional
    public UnreadCount markAllRead(String recipient) {
        rows.markAllRead(recipient, clock.instant());
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
        var event = userId.startsWith(PARENT_PREFIX) ? ChatEvent.parentNotification(schoolId, userId.substring(PARENT_PREFIX.length()), frame)
                : ChatEvent.notification(schoolId, userId, frame);
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
                e.getReadAt() == null ? null : e.getReadAt().toEpochMilli(), e.getCreatedAt().toEpochMilli(), e.getChildId());
    }
}
