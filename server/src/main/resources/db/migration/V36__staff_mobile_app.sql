-- Feature 13 `staff mode in the mobile app`: staff sign in to the same MySchool app the parents use, and the school's
-- non-teaching staff gain a login of their own.
--
-- Additive and idempotent, so a re-run against the QA school creates nothing twice:
--
-- 1. The `staffMobileApp` flag, off until an Admin turns it on for a school. Same shape as `V15__chat.sql`'s `chat`
--    row, which is `V5__flags_themes.sql`'s: `INSERT … WHERE NOT EXISTS`, so the seed is the definition and a second
--    run is a no-op. The repo's flag keys are camelCase (`quest.server.flags.FlagKeys`), which is why it is not the
--    spec's `staff_mobile_app`.
--
-- 2. `users.role` gains WORKER, the fifth role and the first that is not a dashboard role. That change replaces a
--    CHECK constraint, which the migration check allows only under `db/vendor/{vendor}`, so it is the next file:
--    `db/vendor/{postgresql,h2}/V36_1__worker_role.sql`. Nothing in this file needs the new value.
--
-- 3. `workers.user_id` and `staff_devices`. A worker row (V25) deliberately had no account; `user_id` is how one is
--    linked when an Admin enables app access (`POST /admin/workers/{id}/app-access`), nullable because most workers
--    still have none, and unique because an account belongs to exactly one worker. `staff_devices` is `parent_devices`
--    (V32) for staff — one row per FCM registration token — and *is* a tenant table, unlike the parent's: a member of
--    staff belongs to one school, so the row carries `school_id` and the Hibernate `school` filter.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'staffMobileApp', 'Staff sign in to the mobile app (attendance, messages, notifications)', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'staffMobileApp');

ALTER TABLE workers ADD COLUMN IF NOT EXISTS user_id TEXT REFERENCES users(id);

-- One account per worker. A partial index would be tidier, but H2 has none and NULLs are distinct in a unique index
-- on both engines, so the workers who have no account do not collide.
CREATE UNIQUE INDEX IF NOT EXISTS workers_user ON workers(user_id);

CREATE TABLE IF NOT EXISTS staff_devices (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token        TEXT NOT NULL,
    platform     TEXT NOT NULL,
    locale       TEXT,
    app_version  TEXT,
    created_at   TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    CONSTRAINT staff_devices_platform CHECK (platform IN ('ANDROID', 'IOS'))
);

-- `token` is unique across the table: a phone that signs in as somebody else moves to her, the rule `parent_devices`
-- already follows, and `DeviceService` moves it between the two tables for the same reason.
CREATE UNIQUE INDEX IF NOT EXISTS staff_devices_token ON staff_devices(token);
CREATE INDEX IF NOT EXISTS staff_devices_user_seen ON staff_devices(user_id, last_seen_at);
CREATE INDEX IF NOT EXISTS staff_devices_school ON staff_devices(school_id);
