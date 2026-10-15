-- Feature 20 `floor supervisor` (docs/features/20-floor-supervisor-role.md): the one role the school has and MySchool
-- lacked — مشرفة الدور, who looks *across* the classes of her floor rather than down one subject.
--
-- A **floor** is a named group of sections inside one academy (`floors.academy` is a curriculum, as
-- `staff_scopes.curriculum` is), and the Admin assigns one or more supervisors to it.
--
-- 1. `floors` — the group itself, unique by name inside a school so the Admin's list cannot hold two "Second floor"s.
--    The index is on the name as typed (H2 has no expression index); `FloorAdminService` compares case-insensitively
--    against the school's own list before it writes, so "second floor" is refused as a duplicate too.
--
-- 2. `floor_classes` — which sections are on it. The unique index is on `(school_id, section_id)`, not on
--    `(floor_id, section_id)`: a class is on **at most one** floor, so "who supervises 1A" has exactly one answer and
--    the second floor that tries to claim it is a 409 rather than a quietly overlapping scope.
--
-- 3. `floor_supervisors` — who supervises it, unique per `(floor_id, user_id)`. One floor may have several, and one
--    supervisor may hold several floors; `floor_supervisors_user` is the index her own `/floor/**` reads start from.
--
-- 4. The `floorSupervisor` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V41).
--    It stays off until the app build that can decode the new `FLOOR_SUPERVISOR` chat role is distributed.
--
-- `users.role` gains the value itself in `db/vendor/{h2,postgresql}/V42_1__floor_supervisor_role.sql`, for the reason
-- V36_1 lives there: widening the role CHECK means dropping it, which `db/migration` refuses.
--
-- Every table carries `school_id` for the Hibernate `school` filter. Additive and idempotent.

CREATE TABLE IF NOT EXISTS floors (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    name       TEXT NOT NULL,
    academy    TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS floors_school_name ON floors(school_id, name);

CREATE TABLE IF NOT EXISTS floor_classes (
    id         TEXT PRIMARY KEY,
    floor_id   TEXT NOT NULL REFERENCES floors(id) ON DELETE CASCADE,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    section_id TEXT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS floor_classes_section ON floor_classes(school_id, section_id);
CREATE INDEX IF NOT EXISTS floor_classes_floor ON floor_classes(floor_id);

CREATE TABLE IF NOT EXISTS floor_supervisors (
    id         TEXT PRIMARY KEY,
    floor_id   TEXT NOT NULL REFERENCES floors(id) ON DELETE CASCADE,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS floor_supervisors_pair ON floor_supervisors(floor_id, user_id);
CREATE INDEX IF NOT EXISTS floor_supervisors_user ON floor_supervisors(school_id, user_id);

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'floorSupervisor', 'The floor supervisor role: her floor''s staff and children, and her messages', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'floorSupervisor');
