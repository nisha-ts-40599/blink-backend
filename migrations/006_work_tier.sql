-- Work tier is mandatory so SoD unknown_tier_default never fires.

ALTER TABLE sdlc_run
    ADD COLUMN IF NOT EXISTS work_tier SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE sdlc_run
    ADD COLUMN IF NOT EXISTS required_gates JSONB NOT NULL DEFAULT '["G-GROOM","G-PR-MERGE"]'::jsonb;
