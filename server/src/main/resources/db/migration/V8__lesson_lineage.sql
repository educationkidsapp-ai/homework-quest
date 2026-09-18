-- N2.1 review: two things a lesson has to remember about itself.
--
-- 1. `copied_from_lesson_id` — which lesson it is a copy of (`docs/teacher-flow.md` §4's drag-to-copy and §8's
--    publish-to-siblings). Publish used to find "the lesson in that class on that day for that subject" and publish
--    it, which is wrong the moment the class already holds an unrelated draft of its own: the teacher would flip
--    somebody else's lesson live. Lineage makes "the copy of this lesson in 1B" an exact question.
--
-- 2. `analysis_cache_hit` — whether the analysis this lesson needed was already in `analysis_cache` when it ran.
--    The editor's "Analyzed before · 0 tokens" badge was inferred from the source files, which have no row on the
--    text path and none on a copy, so a hand-written lesson that had genuinely paid for its analysis claimed it had
--    not. The flag is written where the answer is actually known — beside the cache lookup itself.
--
-- Both are additive and both have a default, so existing rows are valid without being rewritten.

ALTER TABLE lessons ADD COLUMN copied_from_lesson_id TEXT;
ALTER TABLE lessons ADD COLUMN analysis_cache_hit BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_lessons_copied_from ON lessons(copied_from_lesson_id);

-- Backfill the badge from what the upload path already recorded per file, so a lesson analysed before this
-- migration keeps the badge it was showing. Nothing can be said about the text path retrospectively — those rows
-- stay false, which is the safe direction: the badge claims a saving rather than hiding one.
UPDATE lessons SET analysis_cache_hit = TRUE
WHERE EXISTS (SELECT 1 FROM source_files f
              WHERE f.lesson_id = lessons.id AND f.cache_hit = TRUE AND f.deleted_at IS NULL);
