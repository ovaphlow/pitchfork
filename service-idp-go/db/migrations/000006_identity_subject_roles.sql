CREATE TABLE identity_subject_roles (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    subject_id TEXT NOT NULL
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    role_id TEXT NOT NULL
        REFERENCES identity_roles(id) ON DELETE RESTRICT,
    granted_by_subject_id TEXT
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    created_at DATETIME NOT NULL,
    UNIQUE(subject_id, role_id)
);

CREATE INDEX identity_subject_roles_subject_idx ON identity_subject_roles(subject_id);
CREATE INDEX identity_subject_roles_role_idx ON identity_subject_roles(role_id);
