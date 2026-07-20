CREATE TABLE identity_sessions (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    subject_id TEXT NOT NULL
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    subject_security_version INTEGER NOT NULL CHECK(subject_security_version >= 1),
    token_hash BLOB NOT NULL UNIQUE CHECK(length(token_hash) = 32),
    csrf_token_hash BLOB NOT NULL CHECK(length(csrf_token_hash) = 32),
    session_access TEXT NOT NULL CHECK(session_access IN ('完整', '仅改密')),
    authenticated_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    idle_expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    revoked_reason TEXT CHECK(
        revoked_reason IS NULL OR
        revoked_reason IN ('用户退出', '主体禁用', '凭据变更', '权限收回', '管理员撤销')
    ),
    metadata TEXT NOT NULL DEFAULT '{}'
        CHECK(json_valid(metadata))
        CHECK(json_type(metadata) = 'object'),
    created_at DATETIME NOT NULL,
    CHECK(
        (revoked_at IS NULL AND revoked_reason IS NULL) OR
        (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL)
    )
);

CREATE INDEX identity_sessions_subject_idx ON identity_sessions(subject_id);
