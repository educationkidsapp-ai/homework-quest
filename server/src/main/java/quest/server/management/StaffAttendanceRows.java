package quest.server.management;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import quest.server.management.Entities.StaffAttendanceEntity;

/**
 * RM5: the two ways one row of the staff register is written, each in a transaction of its own — {@code ChatThreads}'
 * pattern, for its reason.
 *
 * <p><strong>Why not read-then-insert.</strong> `staff_attendance` is unique on `(user_id, date)`. A manager with the
 * register open on two tabs, or a retried request, can have two `PUT`s pass the same "nobody has marked her yet" read
 * and both insert; the loser would get a constraint name in a 500. Here the insert is attempted and the violation is
 * caught by {@link StaffAttendanceService}, which then overwrites the row the winner wrote — the last `PUT` decides,
 * which is what a register is.
 *
 * <p><strong>Why two beans and {@code REQUIRES_NEW}.</strong> A failed flush poisons its persistence context, so the
 * recovery must happen in a <em>different</em> transaction from the one that failed, and a self-call would not go
 * through the proxy at all. Each row is therefore its own transaction: {@link StaffAttendanceService#mark} validates
 * every line of the body before the first one is written, so a body that reaches here is one where each row is
 * independently correct.
 */
@Component
public class StaffAttendanceRows {
    private final StaffAttendanceRepository rows;

    public StaffAttendanceRows(StaffAttendanceRepository rows) { this.rows = rows; }

    /**
     * Inserts the first mark of a day. Throws
     * {@link org.springframework.dao.DataIntegrityViolationException} when another request inserted the same
     * `(user, day)` first — `saveAndFlush`, so the violation arrives here rather than at commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StaffAttendanceEntity insert(String schoolId, String userId, LocalDate date, String status, String note,
                                        String markedBy, Instant now) {
        var row = new StaffAttendanceEntity();
        row.setId(UUID.randomUUID().toString());
        row.setSchoolId(schoolId); row.setUserId(userId); row.setDate(date);
        return rows.saveAndFlush(write(row, status, note, markedBy, now));
    }

    /**
     * Writes a status onto the day's existing row, whoever created it. Used both for a day this manager had already
     * marked and for the row that won a race, so the recovery path needs no second shape.
     *
     * @return the row, or null when it has since been deleted — the caller reads that as "nothing to overwrite"
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StaffAttendanceEntity overwrite(String userId, LocalDate date, String status, String note,
                                           String markedBy, Instant now) {
        return rows.findByUserIdAndDate(userId, date)
                .map(row -> rows.save(write(row, status, note, markedBy, now))).orElse(null);
    }

    private static StaffAttendanceEntity write(StaffAttendanceEntity row, String status, String note,
                                               String markedBy, Instant now) {
        row.setStatus(status); row.setNote(note); row.setMarkedBy(markedBy); row.setMarkedAt(now);
        return row;
    }
}
