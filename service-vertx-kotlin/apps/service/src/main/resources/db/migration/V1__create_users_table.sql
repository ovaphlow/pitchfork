CREATE TABLE IF NOT EXISTS users (
    id                VARCHAR(32) PRIMARY KEY,
    email             VARCHAR(255) UNIQUE NOT NULL,
    username          VARCHAR(128) NOT NULL DEFAULT '',
    phone             VARCHAR(32) NOT NULL DEFAULT '',
    password_hash     VARCHAR(255) NOT NULL,
    user_type         VARCHAR(32) NOT NULL DEFAULT 'regular',
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    security_info     JSONB NOT NULL DEFAULT '{}'::jsonb,
    verification_info JSONB NOT NULL DEFAULT '{}'::jsonb,
    password_reset_info JSONB NOT NULL DEFAULT '{}'::jsonb,
    activity_info     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);
