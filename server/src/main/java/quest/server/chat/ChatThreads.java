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
 * same moment — one from each side — resolve on the unique (child, staff) index into one thread: the loser's
 * insert fails, its own small transaction rolls back, and it re-reads the winner's row while the caller's
 * transaction stays usable. A thread with no message yet is harmless.
 */
@Component
public class ChatThreads {
    private final ChatThreadRepository threads; private final Clock clock;
    public ChatThreads(ChatThreadRepository threads, Clock clock) { this.threads = threads; this.clock = clock; }

    /**
     * The parent's thread with one staff member. {@code staffRole} says which kind she is and {@code topic} is read
     * only here, on the row that is being created: a parent marks a conversation a complaint when she opens it, and a
     * later message cannot re-label one the coordinator has already worked on.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entities.ChatThreadEntity getOrCreate(ChildEntity child, String staffId, String staffRole, String topic) {
        var existing = threads.findByChildIdAndTeacherId(child.getId(), staffId);
        if (existing.isPresent()) return existing.get();
        var t = row(child.getSchoolId(), staffId, staffRole, topic);
        t.setChildId(child.getId());
        try { return threads.saveAndFlush(t); }
        catch (DataIntegrityViolationException raced) { return threads.findByChildIdAndTeacherId(child.getId(), staffId).orElseThrow(() -> raced); }
    }

    /**
     * R4: the staff-to-staff thread between two staff members — no child, and the pair de-duplicated by
     * `chat_threads_staff_pair`. Either direction (staff1, staff2) or (staff2, staff1) resolves to the same single row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entities.ChatThreadEntity getOrCreateStaff(String schoolId, String staff1Id, String staff2Id, String staffRole) {
        var existing = threads.findByTeacherIdAndPeerUserId(staff1Id, staff2Id);
        if (existing.isPresent()) return existing.get();
        existing = threads.findByTeacherIdAndPeerUserId(staff2Id, staff1Id);
        if (existing.isPresent()) return existing.get();
        var t = row(schoolId, staff1Id, staffRole, ChatService.QUESTION);
        t.setPeerUserId(staff2Id);
        try { return threads.saveAndFlush(t); }
        catch (DataIntegrityViolationException raced) {
            return threads.findByTeacherIdAndPeerUserId(staff1Id, staff2Id)
                    .or(() -> threads.findByTeacherIdAndPeerUserId(staff2Id, staff1Id))
                    .orElseThrow(() -> raced);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entities.ChatThreadEntity getOrCreateStaff(String schoolId, String coordinatorId, String managerId) {
        return getOrCreateStaff(schoolId, coordinatorId, managerId, ChatService.MANAGERIAL);
    }

    private Entities.ChatThreadEntity row(String schoolId, String staffId, String staffRole, String topic) {
        var t = new Entities.ChatThreadEntity();
        t.setId(UUID.randomUUID().toString()); t.setSchoolId(schoolId); t.setTeacherId(staffId);
        t.setStaffRole(staffRole); t.setTopic(topic); t.setStatus(ChatService.OPEN); t.setCreatedAt(clock.instant());
        return t;
    }
}
