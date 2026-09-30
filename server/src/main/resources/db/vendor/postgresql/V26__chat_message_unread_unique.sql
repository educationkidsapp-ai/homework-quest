-- T1 (review): the "one unread `chat.message` row per thread per recipient" rule, enforced by the database rather
-- than by a read followed by an insert. Two messages landing on one thread at the same moment used to race: both
-- transactions read "she has none unread" and both inserted, and the recipient got two bell entries to clear.
--
-- `lesson_id` carries the **thread's** id on a `chat.message` row (the column is "the row this is about", as it
-- carries the broadcast's id on `broadcast.posted`), so the key is (recipient, kind, thread).
--
-- **Why the index is partial, and why `kind` is in the predicate as well as the key.** Only the unread row is
-- unique: once she has read one, the next message must be able to write a fresh row, and a thread accumulates as
-- many read rows over its life as it has quiet spells. And only `chat.message` is constrained: the three lesson
-- kinds legitimately hold two unread rows for one lesson (an `error`, a retry, a second `error` — each a real
-- transition she wants to see), so a predicate without `kind` would turn that into a failed insert.
--
-- The H2 twin is `db/vendor/h2/V26__chat_message_unread_unique.sql`: H2 has no partial index, so it indexes a
-- computed column that is NULL for every row this predicate excludes, which has the same effect because both
-- engines treat NULLs in a unique index as distinct. That is the whole reason this pair lives under `db/vendor`.
--
-- Idempotent, and safe on existing QA data: the duplicates any pre-index build could have written are marked read
-- (oldest kept unread) before the index is created, so a re-run finds nothing to fix and nothing to create.

UPDATE notifications n SET read_at = now()
WHERE n.kind = 'chat.message' AND n.read_at IS NULL
  AND EXISTS (
    SELECT 1 FROM notifications older
    WHERE older.kind = 'chat.message' AND older.read_at IS NULL
      AND older.user_id = n.user_id AND older.lesson_id = n.lesson_id
      AND (older.created_at, older.id) < (n.created_at, n.id)
  );

CREATE UNIQUE INDEX IF NOT EXISTS notifications_unread_chat_message
    ON notifications (user_id, kind, lesson_id)
    WHERE read_at IS NULL AND kind = 'chat.message';
