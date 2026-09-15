-- Where a lesson's content came from: pdf | slides | images | manual. Existing rows are classified from their files.
ALTER TABLE lessons ADD COLUMN source TEXT NOT NULL DEFAULT 'pdf';
UPDATE lessons l SET source = 'slides' WHERE EXISTS (SELECT 1 FROM source_files f WHERE f.lesson_id = l.id AND f.deleted_at IS NULL AND f.kind = 'pptx');
UPDATE lessons l SET source = 'images' WHERE NOT EXISTS (SELECT 1 FROM source_files f WHERE f.lesson_id = l.id AND f.deleted_at IS NULL AND f.kind IN ('pdf', 'pptx'))
    AND EXISTS (SELECT 1 FROM source_files f WHERE f.lesson_id = l.id AND f.deleted_at IS NULL);
