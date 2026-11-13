-- 0002_job_positions.sql — 招聘岗位
--
-- 岗位不设审核状态机：草稿 → 招聘中 即上线；「已过期」由 expires_at 推导，
-- 不落状态，避免后台定时任务。只有已认证机构的岗位可以进入「招聘中」。

CREATE TABLE IF NOT EXISTS job_positions (
    id                TEXT PRIMARY KEY,
    organization_id   TEXT NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    title             VARCHAR(200) NOT NULL,
    category          VARCHAR(64)  NOT NULL DEFAULT '',
    employment_type   VARCHAR(16)  NOT NULL DEFAULT '全职'
        CHECK (employment_type IN ('全职','兼职','实习','临时','项目制')),
    work_mode         VARCHAR(16)  NOT NULL DEFAULT '坐班'
        CHECK (work_mode IN ('坐班','远程','混合')),
    headcount         INTEGER      NOT NULL DEFAULT 1 CHECK (headcount > 0),
    salary_negotiable BOOLEAN      NOT NULL DEFAULT FALSE,
    salary_min        INTEGER      CHECK (salary_min IS NULL OR salary_min >= 0),
    salary_max        INTEGER      CHECK (salary_max IS NULL OR salary_max >= 0),
    salary_unit       VARCHAR(8)   NOT NULL DEFAULT '月'
        CHECK (salary_unit IN ('时','日','月','年')),
    education         VARCHAR(16)  NOT NULL DEFAULT '不限'
        CHECK (education IN ('不限','高中及以下','大专','本科','硕士','博士')),
    experience        VARCHAR(16)  NOT NULL DEFAULT '不限'
        CHECK (experience IN ('不限','应届生','1-3年','3-5年','5-10年','10年以上')),
    province          VARCHAR(32)  NOT NULL DEFAULT '',
    city              VARCHAR(32)  NOT NULL DEFAULT '',
    district          VARCHAR(32)  NOT NULL DEFAULT '',
    status            VARCHAR(16)  NOT NULL DEFAULT '草稿'
        CHECK (status IN ('草稿','招聘中','已暂停','已关闭')),
    published_at      TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    view_count        BIGINT       NOT NULL DEFAULT 0,
    created_by        TEXT         NOT NULL,
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(metadata) = 'object'),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- 面议：不填区间；非面议：必须有下限，上限不得低于下限。
    CONSTRAINT ck_job_positions_salary CHECK (
        (salary_negotiable AND salary_min IS NULL AND salary_max IS NULL)
        OR (NOT salary_negotiable AND salary_min IS NOT NULL
            AND (salary_max IS NULL OR salary_max >= salary_min))
    ),
    CONSTRAINT ck_job_positions_published CHECK (
        status <> '招聘中' OR published_at IS NOT NULL
    )
);

COMMENT ON COLUMN job_positions.created_by IS 'IDP subject_id，创建该岗位的机构成员';
COMMENT ON COLUMN job_positions.published_at IS '首次进入「招聘中」的时间，用于信息流排序';
COMMENT ON COLUMN job_positions.metadata IS
    '契约：{description, requirements, benefits, address, salary_note, tags[], '
    'attachments[], contact{name,phone,email}, apply{method,value}}；'
    '键名与类型由 Go 侧 struct 校验，禁止写入未定义键';

CREATE INDEX IF NOT EXISTS idx_job_positions_org
    ON job_positions (organization_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_job_positions_feed
    ON job_positions (status, city, published_at DESC);
CREATE INDEX IF NOT EXISTS idx_job_positions_expiry
    ON job_positions (expires_at) WHERE status = '招聘中';
