package quest.server.chat;

import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import quest.server.children.Entities.ChildEntity;

/**
 * The thread row, created on the first message. In a transaction of its own so that two first messages sent at the
 * same moment — one from each side — resolve on the unique (child, teacher) index into one thread: the loser's
 * insert fails, its own small transaction rolls back, and it re-reads the winner's row while the caller's
 * transaction stays usable. A thread with no message yet is harmless.
 */
@Component
public class ChatThreads {
    private final ChatThreadRepository threads; private final Clock clock;
    public ChatThreads(ChatThreadRepository threads, Clock clock) { this.threads = threads; this.clock = clock; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entities.ChatThreadEntity getOrCreate(ChildEntity child, String teacherId) {
        var existing = threads.findByChildIdAndTeacherId(child.getId(), teacherId);
        if (existing.isPresent()) return existing.get();
        var t = new Entities.ChatThreadEntity();
        t.setId(UUID.randomUUID().toString()); t.setSchoolId(child.getSchoolId()); t.setChildId(child.getId()); t.setTeacherId(teacherId);
        t.setCreatedAt(clock.instant());
        try { return threads.saveAndFlush(t); }
        catch (DataIntegrityViolationException raced) { return threads.findByChildIdAndTeacherId(child.getId(), teacherId).orElseThrow(() -> raced); }
    }
}
