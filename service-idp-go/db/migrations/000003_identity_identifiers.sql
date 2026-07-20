CREATE TABLE identity_identifiers (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    subject_id TEXT NOT NULL
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    identifier_type TEXT NOT NULL
        CHECK(identifier_type IN ('账号', '邮箱', '手机号', '工号')),
    identifier_value TEXT NOT NULL CHECK(length(identifier_value) BETWEEN 1 AND 320),
    normalized_value TEXT NOT NULL CHECK(length(normalized_value) BETWEEN 1 AND 320),
    identifier_usage TEXT NOT NULL
        CHECK(identifier_usage IN ('主登录', '辅助登录', '联系')),
    status TEXT NOT NULL CHECK(status IN ('启用', '禁用')),
    verified_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE(identifier_type, normalized_value)
);

CREATE UNIQUE INDEX identity_identifiers_primary_subject_idx
    ON identity_identifiers(subject_id)
    WHERE identifier_usage = '主登录';

CREATE INDEX identity_identifiers_subject_idx ON identity_identifiers(subject_id);

CREATE INDEX identity_identifiers_login_lookup_idx
    ON identity_identifiers(normalized_value)
    WHERE status = '启用'
      AND identifier_usage IN ('主登录', '辅助登录');
