-- N1 (owner decision 2026-10-02): the product is renamed MySchool. The name is data (§A) — `V5__flags_themes.sql`
-- seeded 'Schools Dashboard' / 'Schools' into the one `platform_settings` row — so the rename is a data change, and
-- it is guarded: a name an Admin typed by hand is somebody's decision and stays.
--
-- Two statements, in this order. The name moves only while it still holds the seeded value. The short name follows
-- only when it is still the seeded one *and* the name beside it is now the new default: 'MySchool' as the short form
-- of a name an Admin chose would be wrong. A second run matches no row. Plain SQL, same on PostgreSQL 16 and H2.

UPDATE platform_settings SET name = 'MySchool', updated_at = CURRENT_TIMESTAMP
WHERE id = 'default' AND name = 'Schools Dashboard';

UPDATE platform_settings SET short_name = 'MySchool', updated_at = CURRENT_TIMESTAMP
WHERE id = 'default' AND name = 'MySchool' AND short_name = 'Schools';
