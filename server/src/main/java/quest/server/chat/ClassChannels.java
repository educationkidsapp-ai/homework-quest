package quest.server.chat;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * Feature 32: the class channel's thread row, created on the first post — {@link ChatThreads} one kind over, and in a
 * transaction of its own for the same reason: two first posts sent at the same moment resolve on
 * `class_channels_class` into one channel, the loser's insert fails, its own small transaction rolls back, and it
 * re-reads the winner's row while the caller's transaction stays usable.
 *
 * <p>A channel is <strong>not a conversation</strong>, so `child_id` and `peer_user_id` are null on it and
 * `teacher_id` — NOT NULL since V15 — carries the section's own id: a class id is never a user id, so every
 * `teacher_id = :userId` query in this package goes on meaning exactly what it meant, and {@code classId} is what
 * the channel is read by.
 */
@Component
public class ClassChannels {
    private final ChatThreadRepository threads; private final ClassChannelRepository channels; private final Clock clock;

    public ClassChannels(ChatThreadRepository threads, ClassChannelRepository channels, Clock clock) {
        this.threads = threads; this.channels = channels; this.clock = clock;
    }

    /** The section's channel, or empty while nobody has posted in it — what a read answers, which never creates one. */
    public Optional<Entities.ChatThreadEntity> find(String classId) {
        return channels.findOneByClassId(classId).flatMap(c -> threads.findOneById(c.getThreadId()));
    }

    /** The section's channel, created now if this is its first post. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entities.ChatThreadEntity getOrCreate(ClassEntity section) {
        var existing = find(section.getId());
        if (existing.isPresent()) return existing.get();
        var thread = new Entities.ChatThreadEntity();
        thread.setId(UUID.randomUUID().toString()); thread.setSchoolId(section.getSchoolId());
        thread.setTeacherId(section.getId()); thread.setClassId(section.getId());
        thread.setKind(ChatService.CLASS_CHANNEL); thread.setStaffRole(ChatService.ROLE_TEACHER);
        thread.setTopic(ChatService.QUESTION); thread.setStatus(ChatService.OPEN); thread.setCreatedAt(clock.instant());
        var row = new Entities.ClassChannelEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(section.getSchoolId());
        row.setClassId(section.getId()); row.setThreadId(thread.getId()); row.setCreatedAt(thread.getCreatedAt());
        try {
            threads.saveAndFlush(thread);
            channels.saveAndFlush(row);
            return thread;
        } catch (DataIntegrityViolationException raced) {
            return find(section.getId()).orElseThrow(() -> raced);
        }
    }
}
