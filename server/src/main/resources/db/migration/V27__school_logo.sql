-- S1 (owner's list of 2026-10-01): a school logo the Admin uploads, rather than a URL she has to host somewhere.
-- `logo_path` is the FileStore key (never a URL), `logo_type` the sniffed image type and `logo_updated_at` the
-- version the public `GET /schools/{id}/logo?v=` URL carries, so a replaced logo is a new URL to every cache.
-- All three are null for a school without one — every existing row — whose theme `logoUrl` keeps working as it did.
ALTER TABLE schools ADD COLUMN IF NOT EXISTS logo_path TEXT;
ALTER TABLE schools ADD COLUMN IF NOT EXISTS logo_type TEXT;
ALTER TABLE schools ADD COLUMN IF NOT EXISTS logo_updated_at TIMESTAMP;
