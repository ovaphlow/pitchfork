INSERT INTO roles (id, name, description, is_system) VALUES
('R_ADMIN00000000000000000001', 'admin',     'System administrator with full access', TRUE),
('R_USER0000000000000000000001', 'user',      'Regular user with basic access',        TRUE),
('R_VIEWER00000000000000000001', 'viewer',    'Read-only access',                       TRUE)
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, name, description, resource, action) VALUES
('P_SUPER00000000000000000001', '*:*',                       'Super admin – full access to all resources', '*', '*'),
('P_SETTINGS_READ0000000000001', 'settings:profile:read',    'Read own profile',     'settings', 'profile:read'),
('P_SETTINGS_WRITE000000000001', 'settings:profile:write',   'Update own profile',   'settings', 'profile:write'),
('P_FILES_UPLOAD0000000000001',  'files:upload',             'Upload files',         'files',    'upload'),
('P_FILES_READ00000000000001',   'files:read',               'Read/download files',  'files',    'read'),
('P_FILES_DELETE0000000000001',  'files:delete',             'Delete files',         'files',    'delete'),
('P_PERM_MANAGE00000000000001',  'permissions:manage',       'Manage RBAC',          'permissions', 'manage')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'admin' AND p.name = '*:*'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'user' AND p.name IN ('settings:profile:read', 'settings:profile:write', 'files:upload', 'files:read', 'files:delete')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'viewer' AND p.name IN ('settings:profile:read', 'files:read')
ON CONFLICT DO NOTHING;
