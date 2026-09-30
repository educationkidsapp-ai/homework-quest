-- MH1 `backend/phones-media-plans` (DR6/DR7, the owner's second manager list, items 3–7) — three things the schema
-- has never held: a telephone number, uploaded attachment bytes, and a weekly plan that *is* an image.
--
-- 1. `users.phone` and `parents.phone`. RM5's people directory had to answer "no telephone number is returned because
--    no table holds one"; the owner's items 3, 4 and 5 are that number on the Coordinators, Teachers and Children
--    screens. Nullable with no default and no backfill — nobody has one until she types it — and capped at 20
--    characters, which is E.164's own maximum (`+` and up to 15 digits) with room for nothing else: the server stores
--    the normalised form (`quest.server.platform.Phones`), not what was typed.
--
-- 2. `attachments` — the bytes themselves, addressed by id like `page_images` and `child_media` are, because
--    `/media/**` is the one door that already resolves an id back to something the caller may read
--    (`quest.server.files.MediaAccess`). `storage_path` is the `FileStore` key (a bucket object on QA, a file on
--    disk in the tests); `school_id` carries the Hibernate `school` filter like every other tenant table and is the
--    uploader's own school, never a request parameter. No foreign keys, as in `V15__chat.sql` and `V22__broadcasts.sql`:
--    an attachment outlives the row that referenced it and `UploadRetention` is what sweeps orphans.
--
-- 3. `broadcasts.attachment_id` — the reference V22 could not make. Until now an attachment was free text
--    (`attachment_url`/`attachment_name`, "bytes that already exist"), so nothing could say whether the recipient
--    could actually read them. A row written from here on names an `attachments.id` and both old columns stay exactly
--    as they are, which is why these are new columns rather than a migration of the first two: every QA row keeps
--    saying what it said, and `BroadcastService.view` prefers the reference when there is one.
--
-- Additive and idempotent: `IF NOT EXISTS` throughout, valid on PostgreSQL 16 and on H2 in PostgreSQL mode (the test
-- profile), and re-running it on QA data changes no row.

ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(20);
ALTER TABLE parents ADD COLUMN IF NOT EXISTS phone VARCHAR(20);

CREATE TABLE IF NOT EXISTS attachments (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL,
    uploaded_by  TEXT NOT NULL,
    name         TEXT NOT NULL,
    mime_type    TEXT NOT NULL,
    size_bytes   BIGINT NOT NULL,
    storage_path TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL
);

-- An uploader reads back what she has just uploaded before it is attached to anything; nothing else lists these.
CREATE INDEX IF NOT EXISTS attachments_school_uploader ON attachments(school_id, uploaded_by, created_at DESC);

ALTER TABLE broadcasts ADD COLUMN IF NOT EXISTS attachment_id TEXT;
-- Denormalised beside `attachment_name`, which V22 already stores for the same reason: a feed of fifty rows draws its
-- attachment chip from the row it is reading, and a media type per row would otherwise be a statement per row.
ALTER TABLE broadcasts ADD COLUMN IF NOT EXISTS attachment_type TEXT;

-- "May this caller read attachment X?" is answered by the broadcasts that carry it — one statement, this index.
CREATE INDEX IF NOT EXISTS broadcasts_attachment ON broadcasts(attachment_id);
