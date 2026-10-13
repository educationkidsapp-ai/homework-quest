-- Feature 13: `users.role` gains WORKER, the fifth role and the first that is not a dashboard role. A worker signs in to
-- the mobile app only and is refused on every `/me`, `/teacher/**` and `/admin/**` path (`SecurityConfig`).
--
-- Why under `db/vendor/{vendor}`: the migration check refuses any `ALTER TABLE … DROP` in `db/migration`, and replacing
-- the role CHECK means dropping it — the same reason as R2's `V19__coordinator_role.sql`. V19 left the constraint
-- *named* `users_role_check` on both engines, so unlike V19 this needs no dynamic SQL, and the two copies are identical.
-- Version 36.1 runs right after `db/migration/V36__staff_mobile_app.sql`, which needs nothing from it.
--
-- Idempotent: dropping and re-adding the named CHECK leaves the same constraint however often it runs. Rolling the
-- image back keeps working — the old code simply never writes the new value.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN', 'TEACHER', 'MANAGERIAL', 'COORDINATOR', 'WORKER'));
