-- B5 `backend/chat-attachments-typing`: real files on chat messages (owner's report of 2026-10-03 — "the app shows only
-- the image's NAME"). Until now an attachment was a `[attachment:…]` tag the sender's client wrote into the body and
-- nothing was uploaded; those bodies stay exactly as they are, plain text, and no row is rewritten.
--
-- 1. `attachments.purpose` — `broadcast` (MH1's, the default every existing row takes) or `chat`. A chat upload can never
--    be attached to a broadcast, and a broadcast upload can never be sent in a chat.
-- 2. `attachments.message_id` — the chat message a `chat` upload was sent with, null until it is sent. It is what
--    `GET /media/attachments/{id}` resolves back to a thread and its participants, and what keeps the 24-hour sweep
--    (`UploadRetention`) off it; an upload never sent, or whose message has gone, is swept as an orphan.
-- 3. `attachments.width` / `.height` — an image's pixels, read once at upload, so a client lays out the bubble before
--    the bytes arrive. Null for a PDF and for every row written before B5.
-- 4. `chat_messages.attachments` — the message's `ChatAttachment` list as JSON, written once at send. Denormalised so a
--    page of history, a thread list's `lastMessage` and a socket frame are built from the message row alone, never a
--    statement per message; the `attachments` rows remain the truth for the bytes and for who may read them.
--
-- Additive and idempotent: `IF NOT EXISTS` throughout, valid on PostgreSQL 16 and on H2 in PostgreSQL mode, and a re-run
-- on QA data changes no row.

ALTER TABLE attachments ADD COLUMN IF NOT EXISTS purpose TEXT NOT NULL DEFAULT 'broadcast';
ALTER TABLE attachments ADD COLUMN IF NOT EXISTS message_id TEXT;
ALTER TABLE attachments ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE attachments ADD COLUMN IF NOT EXISTS height INTEGER;
CREATE INDEX IF NOT EXISTS attachments_message ON attachments(message_id);

ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS attachments TEXT;
