-- RBAC core
CREATE TABLE IF NOT EXISTS roles (
    id          VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(128) UNIQUE NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS permissions (
    id          VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(256) UNIQUE NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    resource    VARCHAR(128) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id       VARCHAR(32) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id VARCHAR(32) NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- RBAC scope assignments
CREATE TABLE IF NOT EXISTS rbac_assignments (
    user_id    VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    VARCHAR(32) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    scope_type VARCHAR(50) NOT NULL DEFAULT 'global',
    scope_id   VARCHAR(32) NOT NULL DEFAULT '0',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id, scope_type, scope_id)
);

CREATE INDEX IF NOT EXISTS idx_rbac_assignments_role ON rbac_assignments(role_id);
CREATE INDEX IF NOT EXISTS idx_rbac_assignments_scope ON rbac_assignments(scope_type, scope_id);

-- ReBAC relations (Zanzibar-style)
CREATE TABLE IF NOT EXISTS rebac_relations (
    subject_type VARCHAR(16) NOT NULL,
    subject_id   VARCHAR(32) NOT NULL,
    relation     VARCHAR(50) NOT NULL,
    object_type  VARCHAR(50) NOT NULL,
    object_id    VARCHAR(32) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_type, subject_id, relation, object_type, object_id)
);

CREATE INDEX IF NOT EXISTS idx_rebac_object ON rebac_relations(object_type, object_id);
CREATE INDEX IF NOT EXISTS idx_rebac_subject ON rebac_relations(subject_type, subject_id);

-- ABAC policies
CREATE TABLE IF NOT EXISTS abac_policies (
    id            VARCHAR(32) PRIMARY KEY,
    resource_type VARCHAR(128) NOT NULL,
    action        VARCHAR(64) NOT NULL,
    effect        VARCHAR(16) NOT NULL DEFAULT 'allow',
    priority      INT NOT NULL DEFAULT 0,
    condition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    description   TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_abac_resource_action ON abac_policies(resource_type, action);
CREATE INDEX IF NOT EXISTS idx_abac_priority ON abac_policies(priority DESC);

-- User attributes
CREATE TABLE IF NOT EXISTS user_attributes (
    user_id    VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attr_key   VARCHAR(128) NOT NULL,
    attr_value TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, attr_key)
);
