CREATE TABLE identity_password_credentials (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    subject_id TEXT NOT NULL UNIQUE
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    password_hash TEXT NOT NULL,
    password_revision INTEGER NOT NULL DEFAULT 1 CHECK(password_revision >= 1),
    credential_status TEXT NOT NULL DEFAULT '有效'
        CHECK(credential_status IN ('有效', '需更新', '已作废')),
    changed_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
