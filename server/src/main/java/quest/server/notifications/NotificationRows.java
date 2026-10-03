package quest.server.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import quest.server.notifications.Entities.NotificationEntity;

/**
 * T1 (review): the one place a `chat.message` row is written, and the only one that needs an upsert rather than an
 * insert — "at most one <em>unread</em> row per thread per recipient" is a rule about a set of rows, so reading the
 * set and then inserting is a race: two messages landing on one thread at the same moment both read "none unread"
 * and both insert, and she gets two bell entries to clear.
 *
 * <p><strong>The database decides.</strong> V26 puts a unique index on (recipient, kind, thread) over the unread
 * `chat.message` rows — partial on PostgreSQL, a computed column on H2 — so the second insert cannot succeed. The
 * write is then: refresh the unread row in one statement and answer it; insert only when that statement changed
 * nothing; and if the insert loses the race, refresh the winner's row instead, so the message this call was about is
 * still the one the bell shows.
 *
 * <p><strong>Each step is a physical transaction of its own</strong> ({@link TransactionTemplate} with
 * `REQUIRES_NEW`, which is {@link quest.server.chat.ChatThreads}' reason one step further): a constraint violation
 * poisons the transaction that flushed it, so the recovery cannot run inside it — and neither may it touch the
 * transaction that is committing the message itself. The bell is never the reason a message fails to send.
 */
@Component
public class NotificationRows {
    private final NotificationRepository rows; private final Clock clock; private final TransactionTemplate own;

    public NotificationRows(NotificationRepository rows, Clock clock, PlatformTransactionManager transactions) {
        this.rows = rows; this.clock = clock;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The row and whether this call wrote it. B4 pushes only a {@code fresh} row — a new unread entry, not a refresh of
     * one she has not looked at yet — so a conversation she is not reading is one push, not one per message.
     */
    public record Upsert(NotificationEntity row, boolean fresh) {}

    /** The unread row for that thread, refreshed or freshly written; never two, whoever else is writing at the time. */
    public Upsert upsertUnread(String schoolId, String userId, String kind, String entityId,
                                           String title, String body, String link, String childId) {
        Instant now = clock.instant();
        var refreshed = own.execute(status -> rows.refreshUnread(userId, kind, entityId, title, body, now) > 0
                ? unread(userId, kind, entityId) : null);
        if (refreshed != null) return new Upsert(refreshed, false);
        var fresh = new NotificationEntity();
        fresh.setId(UUID.randomUUID().toString()); fresh.setSchoolId(schoolId); fresh.setUserId(userId); fresh.setKind(kind);
        fresh.setTitle(title); fresh.setBody(body); fresh.setLink(link); fresh.setLessonId(entityId); fresh.setChildId(childId); fresh.setCreatedAt(now);
        try { return new Upsert(own.execute(status -> rows.saveAndFlush(fresh)), true); }
        catch (DataIntegrityViolationException raced) {
            var winner = own.execute(status -> {
                rows.refreshUnread(userId, kind, entityId, title, body, now);
                return unread(userId, kind, entityId);
            });
            if (winner == null) throw raced;
            return new Upsert(winner, false);
        }
    }

    private NotificationEntity unread(String userId, String kind, String entityId) {
        return rows.unreadAbout(userId, kind, entityId).stream().findFirst().orElse(null);
    }
}
