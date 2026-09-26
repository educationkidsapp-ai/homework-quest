-- R4 `backend/coordinator-comms` (DR3) — the C1 thread grows a staff peer that is not always a teacher.
--
-- One table changes, additively, and no table is added: DR3 is "communication reuses the C1–C3 chat", so a parent's
-- conversation with a coordinator and a coordinator's conversation with her manager are `chat_threads` rows like
-- every other, and the Complaints inbox is a query over them rather than a second store (N5.2 stays open).
--
--   * `staff_role` — which staff member holds the dashboard side: `TEACHER` (every row written before R4),
--     `COORDINATOR`, or `MANAGERIAL` for the staff-to-staff shape. The default is what makes this additive: a row
--     inserted by C1's own code path is still a teacher's thread without naming it.
--   * `peer_user_id` — the *second* staff member of a staff-to-staff thread, and NULL on every parent thread.
--     `teacher_id` is deliberately reused as "the staff peer" rather than doubled by a `staff_user_id`: every index,
--     query and unread counter of C1 keeps working, and one nullable column is the whole difference between the two
--     shapes. `teacher_unread` is that column's badge and `parent_unread` is the counterpart's, whoever it is.
--   * `topic` (`question` / `complaint`) and `status` (`open` / `resolved`, with `resolved_at` / `resolved_by`) —
--     the parent labels a conversation when she opens it and only the staff side moves the status.
--
-- `child_id` loses its NOT NULL because a coordinator-to-manager thread is about the department, not about a child.
-- Nothing else about it changes: the parent shapes still carry one, checked by the service rather than by the column.
--
-- Idempotent for the QA data already in place: `IF NOT EXISTS` on every column and index, defaults for the three
-- NOT NULL columns so existing rows need no backfill, and no DML at all.

ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS staff_role    TEXT DEFAULT 'TEACHER'  NOT NULL;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS topic         TEXT DEFAULT 'question' NOT NULL;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS status        TEXT DEFAULT 'open'     NOT NULL;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS peer_user_id  TEXT;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS resolved_at   TIMESTAMP;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS resolved_by   TEXT;

ALTER TABLE chat_threads ALTER COLUMN child_id DROP NOT NULL;

-- One thread per pair of staff members, for the reason `chat_threads_child_teacher` gives for a parent's: two first
-- messages sent at the same moment must resolve into one thread. Both engines treat NULLs as distinct in a unique
-- index (see `V18__staff_scopes.sql`), so every parent thread — `peer_user_id` NULL — passes this index untouched.
CREATE UNIQUE INDEX IF NOT EXISTS chat_threads_staff_pair ON chat_threads(teacher_id, peer_user_id);
-- The manager's side of a staff thread (RM2 reads it) and the coordinator's Complaints inbox.
CREATE INDEX IF NOT EXISTS chat_threads_peer ON chat_threads(peer_user_id, last_message_at);
CREATE INDEX IF NOT EXISTS chat_threads_topic ON chat_threads(school_id, topic, status);
