-- B3 review: two "only once" rules held by the database rather than by a read followed by a write, so two requests
-- (or two Cloud Run instances) racing cannot both succeed. Plain unique indexes over nullable columns — both engines
-- treat NULLs as distinct — so one file serves PostgreSQL and H2, and every row that is not under the rule (a
-- homework attempt, a dashboard notification) carries NULL and is unconstrained.
--
-- `attempts.exam_key` is `child|lesson|stop` on an exam answer: the first answer to a question is the answer, and a
-- second upload of that question — even one racing the first on another device — is ignored (`ON CONFLICT DO
-- NOTHING`). `notifications.once_key` is `recipient|kind|lesson|child` on `exam.released` / `homework.published`: a
-- parent is told once per lesson and child however many times, or on however many instances, the release happens.
--
-- Additive and idempotent. Existing rows keep NULL: answers stored before this ran are still the first answers the
-- service reads, and nothing is backfilled.

ALTER TABLE attempts ADD COLUMN IF NOT EXISTS exam_key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS attempts_exam_key ON attempts(exam_key);

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS once_key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS notifications_once_key ON notifications(once_key);
