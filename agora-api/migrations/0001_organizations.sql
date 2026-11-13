-- 0001_organizations.sql — 机构（公司/医院/学校…）与机构成员
--
-- 约定：
--   * 主键为 26 位 Crockford Base32 ULID，由应用层生成，数据库不提供默认值。
--   * created_at / updated_at 由应用层写入，不使用触发器。
--   * 枚举使用中文值 + CHECK，不建 PostgreSQL ENUM 类型。
--   * 只把「筛选/排序维度、唯一/外键/状态机字段、列表卡片直接展示字段」留在列上，
--     其余展示型与正文内容进 metadata JSONB。

CREATE TABLE IF NOT EXISTS organizations (
    id               TEXT PRIMARY KEY,
    name             VARCHAR(120) NOT NULL,
    short_name       VARCHAR(60)  NOT NULL DEFAULT '',
    org_type         VARCHAR(16)  NOT NULL DEFAULT '企业'
        CHECK (org_type IN ('企业','医院','学校','社会组织','政府机关','其他')),
    logo_file_id     TEXT         NOT NULL DEFAULT '',
    province         VARCHAR(32)  NOT NULL DEFAULT '',
    city             VARCHAR(32)  NOT NULL DEFAULT '',
    district         VARCHAR(32)  NOT NULL DEFAULT '',
    status           VARCHAR(16)  NOT NULL DEFAULT '待审核'
        CHECK (status IN ('待审核','已认证','已驳回','已停用')),
    reject_reason    VARCHAR(200) NOT NULL DEFAULT '',
    owner_subject_id TEXT         NOT NULL,
    reviewed_by      TEXT,
    reviewed_at      TIMESTAMPTZ,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(metadata) = 'object'),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  organizations IS '机构档案：发布招聘岗位的主体';
COMMENT ON COLUMN organizations.logo_file_id     IS 'Nexus files.id';
COMMENT ON COLUMN organizations.owner_subject_id IS 'IDP subject_id，机构所有者';
COMMENT ON COLUMN organizations.reviewed_by      IS 'Nexus 平台管理员 subject_id';
COMMENT ON COLUMN organizations.metadata IS
    '契约：{intro, industry, scale, website, address, credit_code, founded_year, tags[], '
    'contact{name,phone,email}}，键名与类型由 Go 侧 struct 校验，禁止写入未定义键';

CREATE UNIQUE INDEX IF NOT EXISTS uq_organizations_name_city
    ON organizations (name, city);
CREATE INDEX IF NOT EXISTS idx_organizations_owner
    ON organizations (owner_subject_id);
CREATE INDEX IF NOT EXISTS idx_organizations_status
    ON organizations (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_organizations_city
    ON organizations (city, district);

-- 机构成员：多个主体共同维护同一机构的岗位。
CREATE TABLE IF NOT EXISTS organization_members (
    id              TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    subject_id      TEXT NOT NULL,
    member_role     VARCHAR(16) NOT NULL DEFAULT '招聘专员'
        CHECK (member_role IN ('所有者','管理员','招聘专员')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, subject_id)
);

COMMENT ON COLUMN organization_members.member_role IS
    '所有者：可转让/删机构；管理员：可改机构档案与成员；招聘专员：仅维护岗位';

-- 每个机构有且仅有一名所有者。
CREATE UNIQUE INDEX IF NOT EXISTS uq_organization_members_owner
    ON organization_members (organization_id) WHERE member_role = '所有者';
CREATE INDEX IF NOT EXISTS idx_organization_members_subject
    ON organization_members (subject_id);
