SET search_path TO healthcare, public;

CREATE UNIQUE INDEX IF NOT EXISTS uq_encounters_active_elderly_care
    ON encounters (patient_id)
    WHERE encounter_type = 'ELDERLY_CARE' AND status = 'ACTIVE';
