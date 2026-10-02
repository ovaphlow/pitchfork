-- 039：IDP 只保留「平台管理员」这一个不依赖 Nexus 的根权限。
-- identity_roles / identity_subject_roles 是第二套角色体系，其产品语义全部归 Nexus，
-- 控制面准入改由 identity_subjects.is_platform_admin 承担。
--
-- 编号说明：本仓库历史开发库的 schema_migrations 里可能残留其它分支用过的 000010
-- （迁移器只按版本号判重，撞号会被静默跳过），因此本迁移直接用 000011。
ALTER TABLE identity_subjects
    ADD COLUMN is_platform_admin INTEGER NOT NULL DEFAULT 0
    CHECK(is_platform_admin IN (0, 1));

-- 迁移存量：原持有 identity.admin 的主体继续是平台管理员，避免升级后无人能进管理台。
UPDATE identity_subjects
SET is_platform_admin = 1
WHERE id IN (
    SELECT subject_role.subject_id
    FROM identity_subject_roles AS subject_role
    JOIN identity_roles AS role ON role.id = subject_role.role_id
    WHERE role.role_code = 'identity.admin'
);

DROP TABLE identity_subject_roles;
DROP TABLE identity_roles;

CREATE INDEX identity_subjects_platform_admin_idx ON identity_subjects(is_platform_admin);
