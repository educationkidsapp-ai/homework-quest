-- Feature 20: `users.role` gains FLOOR_SUPERVISOR, the sixth role — the floor supervisor (مشرفة الدور), a dashboard
-- role with her own area `/floor/**` and her own attendance, who may be named a direct manager (feature 14).
--
-- Why under `db/vendor/{vendor}`: the migration check refuses any `ALTER TABLE … DROP` in `db/migration`, and
-- replacing the role CHECK means dropping it — the same reason as V36_1's WORKER and R2's `V19__coordinator_role.sql`.
-- V19 left the constraint *named* `users_role_check` on both engines, so like V36_1 this needs no dynamic SQL and the
-- two copies are identical. Version 42.1 runs right after `db/migration/V42__floors.sql`, which needs nothing from it
-- (its `floor_supervisors.user_id` FK names `users(id)`, not a role).
--
-- Idempotent: dropping and re-adding the named CHECK leaves the same constraint however often it runs. Rolling the
-- image back keeps working — the old code simply never writes the new value.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN', 'TEACHER', 'MANAGERIAL', 'COORDINATOR', 'WORKER', 'FLOOR_SUPERVISOR'));
