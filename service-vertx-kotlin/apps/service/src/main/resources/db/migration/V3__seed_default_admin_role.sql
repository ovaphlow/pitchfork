INSERT INTO roles (id, name, description, is_system)
VALUES ('ADMIN0000000000000000000001', 'admin', 'System administrator with full access', TRUE)
ON CONFLICT (name) DO NOTHING;

INSERT INTO roles (id, name, description, is_system)
VALUES ('USER00000000000000000000001', 'user', 'Regular user with basic access', TRUE)
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000001', '*:*', 'Super admin – full access to all resources', '*', '*')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000002', 'settings:profile:read', 'Read own profile', 'settings', 'profile:read')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000003', 'settings:profile:write', 'Update own profile', 'settings', 'profile:write')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000004', 'files:upload', 'Upload files', 'files', 'upload')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000005', 'files:read', 'Read/download files', 'files', 'read')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000006', 'files:delete', 'Delete files', 'files', 'delete')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action)
VALUES ('PERM00000000000000000000007', 'permissions:manage', 'Manage roles and permissions', 'permissions', 'manage')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'admin' AND p.name = '*:*'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'user' AND p.name IN ('settings:profile:read', 'settings:profile:write', 'files:upload', 'files:read', 'files:delete')
ON CONFLICT DO NOTHING;
