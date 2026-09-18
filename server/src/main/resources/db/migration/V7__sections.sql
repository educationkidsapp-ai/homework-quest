-- D14 (docs/plan.md) and `docs/teacher-flow.md` §1–§2: a Class stops being "(curriculum, grade, subject, teacher)"
-- and becomes a **section** — 1A, 1B, 1C inside British Grade 1 — with a join code; who teaches what moves into
-- `teaching_assignments`, one teacher per subject per class. Children sit in a section (`children.class_id`) instead
-- of being matched by curriculum + grade, and a lesson names the teacher who wrote it and whether it is homework or
-- an exam.
--
-- Additive only (`.github/workflows/migration-check.yml` rejects DROP TABLE / DROP COLUMN / RENAME; relaxing a
-- column with `ALTER COLUMN … DROP NOT NULL` is allowed and is what D14 calls for) and valid on PostgreSQL 16 and on
-- H2 in PostgreSQL mode (the test profile). Every statement is written so that re-running it over QA data cannot
-- change a row twice: each backfill carries the guard that says "this row has not been converted yet".
--
-- `classes.subject` and `classes.teacher_id` stay, unused by new rows: the V4 UNIQUE (school_id, curriculum, grade,
-- subject, teacher_id) survives, and NULLs are distinct on both engines, so sibling sections created from here on —
-- which leave both NULL — can never collide with each other or with a legacy row.

-- ---------------------------------------------------------------- 1. sections

ALTER TABLE classes ADD COLUMN name TEXT;                                   -- "1A"; NULL marks a legacy pre-V7 row
ALTER TABLE classes ADD COLUMN join_code TEXT;                              -- 6 characters A–Z0–9, what a parent types
ALTER TABLE classes ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE classes ADD COLUMN join_code_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE classes ALTER COLUMN subject DROP NOT NULL;

CREATE UNIQUE INDEX ux_classes_join_code ON classes(join_code);             -- NULLs are distinct on PG 16 and H2
CREATE INDEX idx_classes_section ON classes(school_id, curriculum, grade, name);

-- One teacher per subject per class (`docs/teacher-flow.md` §2). The unique index is the rule; the Admin API answers
-- a taken pair as 409 naming the current teacher rather than letting the constraint surface.
CREATE TABLE teaching_assignments (
    id TEXT PRIMARY KEY,
    school_id TEXT NOT NULL REFERENCES schools(id),
    teacher_id TEXT NOT NULL REFERENCES users(id),
    class_id TEXT NOT NULL REFERENCES classes(id),
    subject TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (class_id, subject)
);

CREATE INDEX idx_teaching_assignments_school ON teaching_assignments(school_id);
CREATE INDEX idx_teaching_assignments_teacher ON teaching_assignments(school_id, teacher_id);
CREATE INDEX idx_teaching_assignments_class ON teaching_assignments(class_id);

-- ---------------------------------------------------------------- 2. rosters

-- A child is a roster row before she is anybody's account: Admin (or a teacher under `teacher.rosterEdit`) types the
-- name, and the parent's join code links her existing row to an account later — so `parent_id` has to be nullable.
-- `name` is the full name the roster carries; no second column is added for it.
ALTER TABLE children ADD COLUMN class_id TEXT REFERENCES classes(id);
ALTER TABLE children ADD COLUMN parent_email TEXT;
ALTER TABLE children ADD COLUMN photo_url TEXT;
ALTER TABLE children ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE children ALTER COLUMN parent_id DROP NOT NULL;

CREATE INDEX idx_children_class ON children(class_id);

ALTER TABLE lessons ADD COLUMN teacher_id TEXT REFERENCES users(id);
ALTER TABLE lessons ADD COLUMN type TEXT NOT NULL DEFAULT 'homework';       -- homework | exam (N4.3 fills the second)

CREATE INDEX idx_lessons_teacher ON lessons(school_id, teacher_id, date);

-- ---------------------------------------------------------------- 3. school week and timezone

-- The platform's defaults (§6 Platform settings) and a per-school override, both nullable on `schools` so "not set"
-- means "use the platform's". Sunday–Thursday and Asia/Riyadh are the defaults the one seeded school runs on.
ALTER TABLE platform_settings ADD COLUMN school_week_json TEXT NOT NULL DEFAULT '["SUN","MON","TUE","WED","THU"]';
ALTER TABLE platform_settings ADD COLUMN timezone TEXT NOT NULL DEFAULT 'Asia/Riyadh';
ALTER TABLE schools ADD COLUMN school_week_json TEXT;
ALTER TABLE schools ADD COLUMN timezone TEXT;

-- ---------------------------------------------------------------- 4. backfill
--
-- For every existing (school, curriculum, grade) exactly one old `classes` row becomes the section — the one with the
-- smallest id, a choice that is deterministic on both engines and needs no window function inside an UPDATE (H2 has
-- no `UPDATE … FROM`). It is named "<grade>A" and given a join code; its `subject`/`teacher_id` are left exactly as
-- they were, because `ClassEntity` reads neither. The other rows of the group keep everything they had, are marked
-- inactive and keep `name IS NULL`, which is how the entity recognises a legacy row and never lists it.
--
-- The backfilled join code is deterministic ("C" + a 5-digit rank) rather than random on purpose: PostgreSQL and H2
-- share no portable random-string function, and a UNIQUE column cannot be filled with a guess. Every code created
-- from here on comes from `JoinCodes.generate()` in Java and is random.

-- The lesson's author, read from the class it was published into — first, while every class still has a NULL `name`,
-- and before that class id is re-pointed below.
--
-- The `name IS NULL` guard is not decoration: `teacher_id IS NULL` alone is not "not converted yet", because a lesson
-- of a class that had no teacher legitimately ends up with a null author. On a second run its `class_id` is already
-- the section, which has a name — and whose own `teacher_id` is the section head's, so without this guard she would
-- be credited with a lesson she never wrote. Running this <em>before</em> the naming below is what makes the guard
-- mean "nothing in this group has been converted yet" rather than "this row is not the one that became the section".
UPDATE lessons l SET teacher_id = (SELECT k.teacher_id FROM classes k WHERE k.id = l.class_id)
WHERE l.teacher_id IS NULL AND l.class_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM classes k WHERE k.id = l.class_id AND k.name IS NULL);

UPDATE classes c SET
    name = CAST(c.grade AS VARCHAR) || 'A',
    join_code = 'C' || LPAD(CAST(1 + (
        SELECT COUNT(*) FROM classes older
        WHERE older.id < c.id
          AND older.id = (SELECT MIN(g.id) FROM classes g
                          WHERE g.school_id = older.school_id AND g.curriculum = older.curriculum AND g.grade = older.grade)
    ) AS VARCHAR), 5, '0')
WHERE c.name IS NULL
  AND c.id = (SELECT MIN(k.id) FROM classes k
              WHERE k.school_id = c.school_id AND k.curriculum = c.curriculum AND k.grade = c.grade);

-- Who taught what, taken from the old rows: one assignment per (section, subject), the row with the smallest id
-- winning when two teachers shared a subject in the same curriculum and grade (V4's UNIQUE allowed that).
INSERT INTO teaching_assignments (id, school_id, teacher_id, class_id, subject, created_at)
SELECT 'ta:' || k.id, k.school_id, k.teacher_id, sec.id, k.subject, CURRENT_TIMESTAMP
FROM classes k
JOIN classes sec ON sec.school_id = k.school_id AND sec.curriculum = k.curriculum AND sec.grade = k.grade
                AND sec.id = (SELECT MIN(g.id) FROM classes g
                              WHERE g.school_id = k.school_id AND g.curriculum = k.curriculum AND g.grade = k.grade)
WHERE k.teacher_id IS NOT NULL AND k.subject IS NOT NULL
  AND k.id = (SELECT MIN(s.id) FROM classes s
              WHERE s.school_id = k.school_id AND s.curriculum = k.curriculum AND s.grade = k.grade
                AND s.subject = k.subject AND s.teacher_id IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM teaching_assignments t WHERE t.class_id = sec.id AND t.subject = k.subject);

-- Every lesson of a legacy row moves to its group's section. Lessons already on a section (name NOT NULL) are left
-- alone, which is what makes this safe to run over data that has been converted once.
UPDATE lessons l SET class_id = (
    SELECT MIN(g.id) FROM classes g
    WHERE g.school_id = (SELECT k.school_id FROM classes k WHERE k.id = l.class_id)
      AND g.curriculum = (SELECT k.curriculum FROM classes k WHERE k.id = l.class_id)
      AND g.grade = (SELECT k.grade FROM classes k WHERE k.id = l.class_id))
WHERE l.class_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM classes k WHERE k.id = l.class_id AND k.name IS NULL);

-- Children move from "school + curriculum + grade" into the section of that group; one with no matching class keeps
-- a NULL `class_id` and is invisible to the roster screens until Admin puts her in a section.
UPDATE children ch SET class_id = (
    SELECT MIN(g.id) FROM classes g
    WHERE g.school_id = ch.school_id AND g.curriculum = ch.curriculum AND g.grade = ch.grade AND g.name IS NOT NULL)
WHERE ch.class_id IS NULL AND ch.deleted_at IS NULL;

UPDATE classes c SET active = FALSE WHERE c.name IS NULL;

-- ---------------------------------------------------------------- 5. flags
--
-- The seven keys this build adds (`docs/prompts/dashboard-first-one-school.md` §1, §5, §6), all off: `multiSchool`
-- hides the Admin school screens while there is one school, and the rest gate features N2–N4 build. Same shape as
-- `V5__flags_themes.sql`, so re-running seeds nothing twice.
INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT v.flag_key, v.description, v.default_on, v.rollout_stage, CURRENT_TIMESTAMP FROM (
    SELECT 'multiSchool'         AS flag_key, 'More than one school: the Admin school screens and the switcher' AS description, FALSE AS default_on, 'internal' AS rollout_stage UNION ALL
    SELECT 'webPlayer',              'The dashboard web player and its test-parent accounts',        FALSE, 'internal' UNION ALL
    SELECT 'gradebook',              'Gradebook grid, bands and exports',                            FALSE, 'internal' UNION ALL
    SELECT 'openStopMarking',        'Teachers mark open stops and release the marks',               FALSE, 'internal' UNION ALL
    SELECT 'exams',                  'Exams: window, single attempt and result sheets',              FALSE, 'internal' UNION ALL
    SELECT 'teacher.rosterEdit',     'Teachers add and edit children in their own classes',          FALSE, 'internal' UNION ALL
    SELECT 'join.byList',            'Parents join by picking their child from the class list',      FALSE, 'internal'
) v
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = v.flag_key);
