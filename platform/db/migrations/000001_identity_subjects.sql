CREATE TABLE identity_subjects (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    status TEXT NOT NULL CHECK(status IN ('启用', '禁用')),
    security_version INTEGER NOT NULL DEFAULT 1 CHECK(security_version >= 1),
    disabled_at DATETIME,
    metadata TEXT NOT NULL DEFAULT '{}'
        CHECK(json_valid(metadata))
        CHECK(json_type(metadata) = 'object'),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CHECK(
        (status = '启用' AND disabled_at IS NULL) OR
        (status = '禁用' AND disabled_at IS NOT NULL)
    )
);

CREATE INDEX identity_subjects_status_idx ON identity_subjects(status);
