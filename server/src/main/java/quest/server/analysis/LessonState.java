package quest.server.analysis;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.LessonStatus;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.notifications.NotificationService;

/**
 * Status writes in their own transaction so the admin panel sees progress while a job runs.
 *
 * <p>E2 (D26): this is also where the dashboard bell is rung, because it is the one place that knows both the
 * status the lesson had and the one it is getting. {@link NotificationService#onLessonTransition} is handed the
 * old status and writes a row only when the two differ, so a status re-asserted by a batch — `generating` twice —
 * notifies nobody, and a poll, which writes no status at all, can never make a row. The row goes in inside this
 * transaction; the socket frame goes out after it commits.
 */
@Service
public class LessonState {
    private final LessonRepository lessons; private final NotificationService notifications;
    public LessonState(LessonRepository lessons, NotificationService notifications) { this.lessons = lessons; this.notifications = notifications; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void set(String lessonId, LessonStatus status) {
        lessons.findById(lessonId).ifPresent(l -> {
            String was = l.getStatus();
            l.setStatus(name(status)); l.setErrorCode(null); l.setErrorMessage(null); l.setUpdatedAt(Instant.now()); lessons.save(l);
            notifications.onLessonTransition(l, was);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String lessonId, String code, String message) {
        lessons.findById(lessonId).ifPresent(l -> {
            String was = l.getStatus();
            l.setStatus("error"); l.setErrorCode(code); l.setErrorMessage(message); l.setUpdatedAt(Instant.now()); lessons.save(l);
            notifications.onLessonTransition(l, was);
        });
    }

    /** E1 review: nothing is running once the lesson reaches Review, so the strip must not keep a step lit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearCurrentStep(String lessonId) { lessons.setCurrentStep(lessonId, null); }

    /**
     * D25: an atomic increment, because the three levels generate at once and each books its own turns. Read the
     * row, add in Java and save it back and two of every three increments are lost — and the save would carry the
     * whole row, undoing `current_step` another batch thread had just written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addUsage(String lessonId, long used, long saved) {
        lessons.addUsage(lessonId, used, saved, Instant.now());
    }

    public static String name(LessonStatus s) { return s.name().toLowerCase(); }
    public static LessonStatus status(LessonEntity l) { return LessonStatus.valueOf(l.getStatus().toUpperCase()); }
}
