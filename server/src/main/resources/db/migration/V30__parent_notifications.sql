-- B3 `backend/exam-integrity-parent-notifications` (D5): parents get notification rows too.
--
-- A parent's row lives in the same `notifications` table as the dashboard bell, so V26's "one unread `chat.message`
-- per thread per recipient" index and every read path apply to her unchanged. Her `user_id` is `parent:<parentId>`:
-- the prefix keeps the two id spaces apart (no `users.id` starts with it), so a staff query can never match a
-- parent's row nor a parent's a staff row. `school_id` is the child's school, as on every tenant row.
--
-- `child_id` names the child a parent's row is about — a parent may have several, and the app opens that child's
-- thread or report from it. NULL on every dashboard row. No foreign key, as in V17: a notification is a record of
-- something that happened and must survive the child being removed.
--
-- Additive and idempotent: a re-run on QA data adds nothing.

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS child_id TEXT;
