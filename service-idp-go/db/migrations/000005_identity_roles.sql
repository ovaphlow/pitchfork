CREATE TABLE identity_roles (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    role_code TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    description TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
