CREATE TABLE IF NOT EXISTS settings (
    id          VARCHAR(32) PRIMARY KEY,
    category    VARCHAR NOT NULL,
    code        VARCHAR NOT NULL,
    root_code   VARCHAR DEFAULT ''::character varying NOT NULL,
    parent_code VARCHAR DEFAULT ''::character varying NOT NULL,
    payload     JSONB NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_settings_category ON settings(category);
CREATE INDEX IF NOT EXISTS idx_settings_category_code ON settings(category, code);
