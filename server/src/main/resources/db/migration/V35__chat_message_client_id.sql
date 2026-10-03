-- B5b `backend/chat-image-metadata`: a send retried after its response was lost is the same message, not a second one.
--
-- `chat_messages.client_id` is the `clientId` the sender's client put on the send (REST body or socket command) — the
-- id its pending bubble already carries. A later send by the same sender into the same thread with the same `clientId`
-- answers the message stored the first time instead of writing another (and is `409 client_id_reused` when it says
-- something else), which matters most for a send with files: the
-- files are already bound to the first message, so the retry used to be `409 attachment_already_sent`. Null for every
-- row written before B5b and for a send that names no `clientId`, which is never deduplicated.
--
-- Additive and idempotent, valid on PostgreSQL 16 and on H2 in PostgreSQL mode; a re-run on QA data changes no row.

ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS client_id TEXT;
-- Unique, so one (thread, sender, clientId) is one row whatever races. A partial index (`WHERE client_id IS NOT NULL`)
-- is not H2's, and is not needed: PostgreSQL and H2 both treat NULLs as distinct in a unique index, so every row with no
-- `client_id` — all of QA's — stays legal.
CREATE UNIQUE INDEX IF NOT EXISTS chat_messages_sender_client ON chat_messages(thread_id, sender_id, client_id);
