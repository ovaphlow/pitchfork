CREATE TABLE identity_audit_events (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    event_action TEXT NOT NULL CHECK(event_action IN (
        '登录',
        '退出登录',
        '主体创建',
        '主体状态变更',
        '标识符变更',
        '凭据变更',
        '角色授予',
        '角色撤销',
        '会话撤销',
        '管理员恢复',
        '维护清理'
    )),
    outcome TEXT NOT NULL CHECK(outcome IN ('成功', '失败')),
    actor_subject_id TEXT
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    target_subject_id TEXT
        REFERENCES identity_subjects(id) ON DELETE RESTRICT,
    request_id TEXT,
    source_hash BLOB CHECK(source_hash IS NULL OR length(source_hash) = 32),
    metadata TEXT NOT NULL DEFAULT '{}'
        CHECK(json_valid(metadata))
        CHECK(json_type(metadata) = 'object'),
    created_at DATETIME NOT NULL
);

CREATE INDEX identity_audit_events_created_at_idx ON identity_audit_events(created_at);
CREATE INDEX identity_audit_events_actor_subject_idx ON identity_audit_events(actor_subject_id);
CREATE INDEX identity_audit_events_target_subject_idx ON identity_audit_events(target_subject_id);
