-- Feature 19 `chat office hours` (docs/features/19-chat-office-hours.md): a parent's message to a member of staff
-- outside her office hours (feature 14's work hours for her category, Ramadan hours included, holidays and days off
-- excluded) is delivered as usual, but its push is quiet, and she gets one summary when her next working day starts.
--
-- 1. The `chatOfficeHours` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V36).
--
-- 2. `schools.phone` — the number the parent's banner gives "for urgent matters". Nullable: a school that has not
--    typed one shows the banner without the line.
--
-- 3. `users.after_hours_alerts` — a staff member's own choice, in the app's Account, to keep normal alerts after
--    hours. Off by default.
--
-- 4. `chat_after_hours` — one row per parent message that arrived outside the recipient's office hours.
--    `due_day` is her next working day in the school's zone; `OfficeHoursSummaryJob` claims a recipient's due rows
--    by setting `summarized_at` where it is still null and sends the summary only when it claimed some, so a rerun
--    or a second instance sends it once. `message_id` is unique, so a message is never counted twice.
--
-- Every table carries `school_id` for the Hibernate `school` filter. Additive and idempotent.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'chatOfficeHours', 'Parent messages outside office hours arrive quietly, with a morning summary', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'chatOfficeHours');

ALTER TABLE schools ADD COLUMN IF NOT EXISTS phone TEXT;

ALTER TABLE users ADD COLUMN IF NOT EXISTS after_hours_alerts BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS chat_after_hours (
    id             TEXT PRIMARY KEY,
    school_id      TEXT NOT NULL REFERENCES schools(id),
    user_id        TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    thread_id      TEXT NOT NULL,
    message_id     TEXT NOT NULL,
    received_at    TIMESTAMP NOT NULL,
    due_day        DATE NOT NULL,
    summarized_at  TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS chat_after_hours_message ON chat_after_hours(message_id);
CREATE INDEX IF NOT EXISTS chat_after_hours_due ON chat_after_hours(school_id, due_day, summarized_at);
