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

CREATE TABLE IF NOT EXISTS user_roles (
    user_id    VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    VARCHAR(32) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_conditions (
    id            VARCHAR(32) PRIMARY KEY,
    role_id       VARCHAR(32) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id VARCHAR(32) REFERENCES permissions(id) ON DELETE CASCADE,
    attribute     VARCHAR(256) NOT NULL,
    operator      VARCHAR(32) NOT NULL,
    value         JSONB NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_role_conditions_role_id ON role_conditions(role_id);
CREATE INDEX IF NOT EXISTS idx_role_conditions_permission_id ON role_conditions(permission_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id ON role_permissions(role_id);
