CREATE TABLE identity_email_login_links (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    subject_id TEXT NOT NULL
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    identifier_id TEXT NOT NULL
        REFERENCES identity_identifiers(id) ON DELETE RESTRICT,
    token_hash BLOB NOT NULL UNIQUE CHECK(length(token_hash) = 32),
    redirect_uri TEXT NOT NULL,
    email_hash BLOB NOT NULL CHECK(length(email_hash) = 32),
    source_hash BLOB NOT NULL CHECK(length(source_hash) = 32),
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME,
    created_at DATETIME NOT NULL
);

CREATE INDEX identity_email_login_links_subject_idx
    ON identity_email_login_links(subject_id);
CREATE INDEX identity_email_login_links_email_idx
    ON identity_email_login_links(email_hash, created_at);
CREATE INDEX identity_email_login_links_source_idx
    ON identity_email_login_links(source_hash, created_at);
CREATE INDEX identity_email_login_links_expires_idx
    ON identity_email_login_links(expires_at);
