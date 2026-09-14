package quest.server.analysis;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.LessonStatus;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;

/** Status writes in their own transaction so the admin panel sees progress while a job runs. */
@Service
public class LessonState {
    private final LessonRepository lessons;
    public LessonState(LessonRepository lessons) { this.lessons = lessons; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void set(String lessonId, LessonStatus status) {
        lessons.findById(lessonId).ifPresent(l -> { l.setStatus(name(status)); l.setErrorCode(null); l.setErrorMessage(null); l.setUpdatedAt(Instant.now()); lessons.save(l); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String lessonId, String code, String message) {
        lessons.findById(lessonId).ifPresent(l -> { l.setStatus("error"); l.setErrorCode(code); l.setErrorMessage(message); l.setUpdatedAt(Instant.now()); lessons.save(l); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addUsage(String lessonId, long used, long saved) {
        lessons.findById(lessonId).ifPresent(l -> { l.setTokenUsage(l.getTokenUsage() + used); l.setTokensSaved(l.getTokensSaved() + saved); l.setUpdatedAt(Instant.now()); lessons.save(l); });
    }

    public static String name(LessonStatus s) { return s.name().toLowerCase(); }
    public static LessonStatus status(LessonEntity l) { return LessonStatus.valueOf(l.getStatus().toUpperCase()); }
}
