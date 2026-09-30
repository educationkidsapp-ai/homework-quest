-- T1 (review): the H2 twin of `db/vendor/postgresql/V26__chat_message_unread_unique.sql`, which carries the
-- reasoning and is the file QA and production run. Same effect, different mechanism.
--
-- **H2 has no partial index.** `CREATE UNIQUE INDEX … WHERE …` is PostgreSQL's; H2 2.x accepts no predicate on an
-- index at all. The filtered equivalent is a *computed* column that is NULL for exactly the rows the PostgreSQL
-- predicate excludes — a read row, or any kind other than `chat.message` — plus an ordinary unique index on it.
-- Both engines treat NULLs in a unique index as distinct, so the excluded rows are unconstrained in both and the
-- constrained set is identical: one unread `chat.message` per (recipient, thread).
--
-- The column is computed, never written: Hibernate does not map it (`NotificationEntity` has no such field), H2
-- re-evaluates it on every insert and update, and `ddl-auto: validate` ignores a column no entity claims. So
-- clearing `read_at`'s NULL — she read the thread — takes the row out of the index by itself.

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS unread_chat_key VARCHAR(600)
    AS (CASE WHEN read_at IS NULL AND kind = 'chat.message' THEN user_id || ':' || COALESCE(lesson_id, '') END);

CREATE UNIQUE INDEX IF NOT EXISTS notifications_unread_chat_message ON notifications(unread_chat_key);
