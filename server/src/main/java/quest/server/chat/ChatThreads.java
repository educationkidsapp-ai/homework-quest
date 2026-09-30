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
     * R4: a staff-to-staff thread — no child, and the pair de-duplicated by `chat_threads_staff_pair` the way the
     * parent's is by `chat_threads_child_teacher`. The **subordinate** is always the `teacherId` side, so whichever
     * of the two writes first gets one row.
     */
    public Entities.ChatThreadEntity getOrCreateStaff(String schoolId, String subordinateId, String supervisorId) {
        return getOrCreateStaff(schoolId, subordinateId, supervisorId, ChatService.MANAGERIAL);
    }

    /**
     * T1b: the same row with the peer's role named — `COORDINATOR` on the thread a teacher opens with a coordinator
     * of her subjects, `MANAGERIAL` on the three pairs R4 and RM2 wrote. `staff_role` names the *peer*, which is the
     * rule the whole staff half keeps, so a client can tell a teacher's two staff threads apart on the row alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entities.ChatThreadEntity getOrCreateStaff(String schoolId, String subordinateId, String supervisorId, String peerRole) {
        var existing = threads.findByTeacherIdAndPeerUserId(subordinateId, supervisorId);
        if (existing.isPresent()) return existing.get();
        var t = row(schoolId, subordinateId, peerRole, ChatService.QUESTION);
        t.setPeerUserId(supervisorId);
        try { return threads.saveAndFlush(t); }
        catch (DataIntegrityViolationException raced) { return threads.findByTeacherIdAndPeerUserId(subordinateId, supervisorId).orElseThrow(() -> raced); }
    }

    private Entities.ChatThreadEntity row(String schoolId, String staffId, String staffRole, String topic) {
        var t = new Entities.ChatThreadEntity();
        t.setId(UUID.randomUUID().toString()); t.setSchoolId(schoolId); t.setTeacherId(staffId);
        t.setStaffRole(staffRole); t.setTopic(topic); t.setStatus(ChatService.OPEN); t.setCreatedAt(clock.instant());
        return t;
    }
}
