-- Feature 36 `school structure builder` (docs/features/36-school-structure-builder.md): the Admin models the school
-- as it really is — academies such as British and American, each with its own curriculum settings, grade naming,
-- grading scheme, terms and subjects — and reads one visual tree of who is responsible for what.
--
-- 1. The `schoolStructure` switch, seeded **on** — D30 (V54): a switch is a kill switch now, and
--    `FlagAdminTest.every_flag_except_multiSchool_is_on_by_default` is what fails when a new one is seeded off.
--
-- 2. `academies` — **the departments of milestone 06, given a row**. Today a department is only a curriculum key
--    (`british`, `american`) repeated on `courses`, `classes`, `children`, `teachers` and the managers'
--    `staff_scopes` rows; this table gives that key a row of its own and every existing `curriculum` column keeps
--    pointing at it by the same key. There is deliberately no second, parallel concept and **no new key**: the
--    curriculum is a closed enum on the wire (`quest.api.dto.Curriculum`) and in the released app builds, so a third
--    academy is a contract change and a leftover of this feature. Keyed `(school_id, academy_key)` — `academy_key`
--    rather than `key`, which H2 reserves.
--    `director_id` is the academy director of feature 14: the department manager, a MANAGERIAL `staff_scopes` row
--    with that curriculum. It is a cache of that row for reading the tree in one statement; the `staff_scopes` row
--    stays the truth, and `AcademyService` writes both.
--    The grade naming is `(grade_style, grade_offset, grade_word_en, grade_word_ar)`: a label is the word plus
--    `grade + offset`, so British `YEAR` with offset 1 shows grade 1 as "Year 2" where American `GRADE` with
--    offset 0 shows it as "Grade 1" (the spec's own example).
--
-- 3. `academy_subjects` — which of the existing `Subject` keys an academy offers, with **its own** English and
--    Arabic name for each and a `ministry` flag for the shared Ministry subjects. `Subject` is a closed enum too
--    (six values), so this is a choice among those keys and never a new one; a brand-new subject key is the other
--    leftover.
--
-- 4. `academy_terms` — a term's name in both languages and its dates, per school year. No two terms of one academy
--    overlap inside one year, which `AcademyService` checks (dates are easier to compare in Java than in a
--    constraint that has to hold on both PostgreSQL 16 and H2).
--
-- 5. `grading_schemes` / `grading_bands` — one scheme per academy (`LETTER` or `NUMERIC`), each band carrying its
--    lower threshold over 0–100 and its name in both languages. **An academy without a scheme uses the default**,
--    which is exactly today's four bands in `grading/Bands.java` (emerging, developing, secure, exceeding at 0, 40,
--    60 and 85) — so no row here is seeded and existing data keeps reading exactly as it does now
--    (`GradingSchemes.DEFAULT`).
--
-- The seed walks every curriculum key **already in use** in each school — its classes, its children and its
-- `staff_scopes` rows — so the British and American departments appear as academies with their directors and
-- nothing that used them changes.
--
-- Every table carries `school_id` for the Hibernate `school` filter, and `IsolationTest` has a case for all five.
-- Additive and idempotent: re-running it over QA data adds a switch that is on, five tables and no row twice. Valid
-- on PostgreSQL 16 and on H2 in PostgreSQL mode (the test profile): no partial index, no UUID function, and the ids
-- are derived from the keys they belong to.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'schoolStructure', 'The Admin models the school as academies, grades, classes and assignments in one tree', TRUE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'schoolStructure');

CREATE TABLE IF NOT EXISTS academies (
    id               TEXT PRIMARY KEY,
    school_id        TEXT NOT NULL REFERENCES schools(id),
    academy_key      TEXT NOT NULL,
    name_en          TEXT NOT NULL,
    name_ar          TEXT NOT NULL,
    curriculum_type  TEXT NOT NULL,
    grade_style      TEXT NOT NULL,
    grade_offset     INTEGER NOT NULL DEFAULT 0,
    grade_word_en    TEXT NOT NULL,
    grade_word_ar    TEXT NOT NULL,
    director_id      TEXT,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS academies_once ON academies(school_id, academy_key);

CREATE TABLE IF NOT EXISTS academy_subjects (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    academy_key  TEXT NOT NULL,
    subject_key  TEXT NOT NULL,
    name_en      TEXT NOT NULL,
    name_ar      TEXT NOT NULL,
    ministry     BOOLEAN NOT NULL DEFAULT FALSE,
    position     INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS academy_subjects_once ON academy_subjects(school_id, academy_key, subject_key);

CREATE TABLE IF NOT EXISTS academy_terms (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    academy_key  TEXT NOT NULL,
    school_year  TEXT NOT NULL,
    name_en      TEXT NOT NULL,
    name_ar      TEXT NOT NULL,
    starts_on    DATE NOT NULL,
    ends_on      DATE NOT NULL,
    created_at   TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS academy_terms_academy ON academy_terms(school_id, academy_key, school_year);

CREATE TABLE IF NOT EXISTS grading_schemes (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    academy_key  TEXT NOT NULL,
    kind         TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS grading_schemes_once ON grading_schemes(school_id, academy_key);

CREATE TABLE IF NOT EXISTS grading_bands (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    scheme_id   TEXT NOT NULL,
    band_key    TEXT NOT NULL,
    lower_bound INTEGER NOT NULL,
    name_en     TEXT NOT NULL,
    name_ar     TEXT NOT NULL,
    position    INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS grading_bands_scheme ON grading_bands(school_id, scheme_id);

-- One academy per curriculum key a school already uses, with its director taken from the MANAGERIAL `staff_scopes`
-- row for that key. British is the `YEAR` style with offset 1 and American the `GRADE` style with offset 0; any
-- other key that somehow exists keeps the `GRADE` style and `OTHER` as its descriptive curriculum type.
INSERT INTO academies (id, school_id, academy_key, name_en, name_ar, curriculum_type, grade_style, grade_offset,
                       grade_word_en, grade_word_ar, director_id, created_at, updated_at)
SELECT u.school_id || ':' || u.academy_key,
       u.school_id,
       u.academy_key,
       CASE u.academy_key WHEN 'british' THEN 'British' WHEN 'american' THEN 'American' ELSE u.academy_key END,
       CASE u.academy_key WHEN 'british' THEN 'بريطاني' WHEN 'american' THEN 'أمريكي' ELSE u.academy_key END,
       CASE u.academy_key WHEN 'british' THEN 'BRITISH' WHEN 'american' THEN 'AMERICAN' ELSE 'OTHER' END,
       CASE u.academy_key WHEN 'british' THEN 'YEAR' ELSE 'GRADE' END,
       CASE u.academy_key WHEN 'british' THEN 1 ELSE 0 END,
       CASE u.academy_key WHEN 'british' THEN 'Year' ELSE 'Grade' END,
       CASE u.academy_key WHEN 'british' THEN 'السنة' ELSE 'الصف' END,
       (SELECT MIN(s.user_id) FROM staff_scopes s JOIN users us ON us.id = s.user_id
         WHERE s.school_id = u.school_id AND s.curriculum = u.academy_key AND s.subject IS NULL AND us.role = 'MANAGERIAL'),
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (
    SELECT DISTINCT k.school_id AS school_id, k.curriculum AS academy_key FROM classes k WHERE k.curriculum IS NOT NULL
    UNION
    SELECT DISTINCT c.school_id, c.curriculum FROM children c WHERE c.curriculum IS NOT NULL AND c.deleted_at IS NULL
    UNION
    SELECT DISTINCT s.school_id, s.curriculum FROM staff_scopes s WHERE s.curriculum IS NOT NULL
) u
WHERE EXISTS (SELECT 1 FROM schools sc WHERE sc.id = u.school_id)
  AND NOT EXISTS (SELECT 1 FROM academies a WHERE a.school_id = u.school_id AND a.academy_key = u.academy_key);
