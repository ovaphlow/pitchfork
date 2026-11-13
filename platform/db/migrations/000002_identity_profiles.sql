CREATE TABLE identity_profiles (
    subject_id TEXT PRIMARY KEY
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 1 AND 120),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
