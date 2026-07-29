SET search_path TO healthcare, public;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'healthcare'
          AND table_name = 'encounters'
          AND column_name = 'encounter_no'
    ) THEN
        ALTER TABLE encounters ADD COLUMN encounter_no VARCHAR(64);
    END IF;
END $$;

UPDATE encounters
SET encounter_no = 'LEGACY-' || id
WHERE encounter_no IS NULL;

ALTER TABLE encounters ALTER COLUMN encounter_no SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_encounters_encounter_no'
          AND conrelid = 'healthcare.encounters'::regclass
    ) THEN
        ALTER TABLE encounters
            ADD CONSTRAINT uq_encounters_encounter_no UNIQUE (encounter_no);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_encounters_encounter_no ON encounters(encounter_no);
