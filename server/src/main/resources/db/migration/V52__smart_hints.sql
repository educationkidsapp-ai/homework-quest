-- Feature 30 `smart hints and targeted practice` (docs/features/30-smart-hints-targeted-practice.md): step-by-step
-- hints on every question and a short practice set per weak skill. **Both are written only by the lesson's `HINTS`
-- pipeline step, at build time, and neither reaches a child before the teacher has approved it** — which is why
-- `approved_by` / `approved_at` are nullable here and why `LessonStore` publishes nothing without them.
--
-- 1. The `smartHints` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V46).
--
-- 2. `stop_hints` — the two graded hints of one stop (a nudge, then a stronger pointer). `hints_json` is a JSON array
--    of strings; the validator keeps each one inside Prompt B's 90-character `hint` limit and rejects any that
--    contains the stop's own answer. Editing them clears the approval, so the column pair is the whole of "the
--    teacher has read this".
--
--    `stop_id` carries **no foreign key**, on purpose: the three questions inside an exitTicket are stops a child
--    answers and so stops that need hints, but `LessonStore.savePlay` writes a `stops` row for the top-level stops
--    only — a key into that table would refuse exactly the questions the exit ticket is made of. What keeps the rows
--    honest instead is the lesson key below (a deleted lesson takes its hints with it) and `LessonStore.savePlay`,
--    which drops the hints of every stop a rewritten play no longer has.
--
-- 3. `practice_plays` — which of a lesson's plays is a targeted practice set, and which confirmed skill it practises.
--    The play itself is an ordinary row of `plays` (level 1, variant 2 and up, which is why `Play.schema.json` now
--    allows a variant above 1), so nothing new stores stops; this table only says what that play is for and whether
--    it may be published. Rejecting one deletes its `plays` row, and the cascade takes this one with it.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. Additive and idempotent: re-running
-- it over QA data adds a switch that is off and two empty tables.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'smartHints', 'Teacher-approved hints on every question and a practice set for each weak skill', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'smartHints');

CREATE TABLE IF NOT EXISTS stop_hints (
    stop_id     TEXT PRIMARY KEY,
    lesson_id   TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    hints_json  TEXT NOT NULL,
    approved_by TEXT,
    approved_at TIMESTAMP,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS stop_hints_lesson ON stop_hints(school_id, lesson_id);

CREATE TABLE IF NOT EXISTS practice_plays (
    play_id     TEXT PRIMARY KEY REFERENCES plays(id) ON DELETE CASCADE,
    lesson_id   TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    skill_id    TEXT NOT NULL,
    approved_by TEXT,
    approved_at TIMESTAMP,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS practice_plays_lesson ON practice_plays(school_id, lesson_id);
CREATE INDEX IF NOT EXISTS practice_plays_skill ON practice_plays(school_id, lesson_id, skill_id);
