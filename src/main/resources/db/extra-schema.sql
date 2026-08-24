-- Wizard data lives in project + stakeholder only.

ALTER TABLE IF EXISTS project ADD COLUMN IF NOT EXISTS project_type VARCHAR(30);
ALTER TABLE IF EXISTS project ADD COLUMN IF NOT EXISTS description TEXT;

-- project_id may already exist as UUID from an earlier patch; a startup
-- aligner converts it to BIGINT so it matches project.id.
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS role_code VARCHAR(50);
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS person_name VARCHAR(255);
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS person_email VARCHAR(255);
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS created_by UUID;
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS updated_by UUID;
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE IF EXISTS stakeholder ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
