-- B4 `backend/fcm-push`: the phones a parent receives push notifications on.
--
-- One row per Firebase Cloud Messaging registration token. `token` is unique: a phone signed in as a second parent
-- moves its row to her (`POST /me/devices` upserts by token), so it never shows the first parent's news. A row goes when
-- the app signs out (`POST /me/devices/unregister`, the token in the body), when FCM answers that the token is dead (`UNREGISTERED`,
-- `INVALID_ARGUMENT`), and when the parent registers an eleventh phone — the one seen longest ago is dropped.
--
-- Not a tenant table: a parent belongs to no school (her children do), as `parents` itself. Every read starts from
-- `parent_id`, which the server takes from her Firebase token, never from a request. The rows go with the parent.
--
-- Additive and idempotent: a re-run on QA data creates nothing twice.

CREATE TABLE IF NOT EXISTS parent_devices (
    id           TEXT PRIMARY KEY,
    parent_id    TEXT NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
    token        TEXT NOT NULL,
    platform     TEXT NOT NULL,
    locale       TEXT,
    app_version  TEXT,
    created_at   TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    CONSTRAINT parent_devices_platform CHECK (platform IN ('ANDROID', 'IOS'))
);

CREATE UNIQUE INDEX IF NOT EXISTS parent_devices_token ON parent_devices(token);
CREATE INDEX IF NOT EXISTS parent_devices_parent_seen ON parent_devices(parent_id, last_seen_at);
