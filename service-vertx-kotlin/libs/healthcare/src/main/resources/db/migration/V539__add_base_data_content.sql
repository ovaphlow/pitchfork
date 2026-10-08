-- =====================================================
-- Healthcare — 基础数据内容表（054 F2，V539）
--
-- 背景（054 §3 Dh / §4 F2 / §8.6）：
--   疫苗排程与发育量表原先**硬编码为常量**（`ChildVaccinationService.VACCINATION_SCHEDULE`、
--   `DevelopmentalScaleCatalog.scales`）。F2 新增内容表承载可维护的基础数据，
--   两个消费方改为**读库优先、常量回落**：
--     - 内容表有启用记录 → 用库内容；
--     - 内容表为空 / 读失败 → **回落既有常量**（052/053 已交付的接种状态与筛查判读结果不变）。
--   本迁移只新增两张内容表，**不修改**任何既有表。
--
-- 口径：
--   1. `status` = 启用/停用（中文枚举，应用层白名单管控，不加 CHECK）。
--   2. 疫苗类型：`code` 唯一；`dose_count` ≥ 1；`recommended_months` 为**剂次对应的推荐月龄数组**
--      （长度必须等于 `dose_count`，应用层校验）；排程条目由「code × 剂次 1..dose_count」展开。
--   3. 发育量表：`(scale_code, scale_version)` 唯一；`definition` JSONB 形状
--      `{"domains":[{"code","name"}],"items":[{"code","domain","prompt","min_months","max_months",
--      "options":[{"value","label"}]}]}`（应用层校验，畸形行跳过并回落常量）。
--   4. `created_by` / `updated_by` 一律取认证主体，客户端不得提交。
--   5. 054 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不新增 jOOQ 引用**。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

-- 疫苗类型（国家免疫规划疫苗排程内容）
CREATE TABLE healthcare.base_vaccination_types (
    id                 VARCHAR(32) PRIMARY KEY,            -- 服务端生成 26 位 ULID
    code               VARCHAR NOT NULL,                   -- 疫苗编码（业务唯一键，如 HEPB）
    name               VARCHAR NOT NULL,                   -- 疫苗中文名
    dose_count         INTEGER NOT NULL DEFAULT 1,         -- 剂次数
    recommended_months JSONB NOT NULL DEFAULT '[]'::jsonb, -- 各剂次推荐月龄数组（长度 = dose_count）
    status             VARCHAR NOT NULL DEFAULT '启用',      -- 启用/停用（应用层白名单）
    sort_order         INTEGER NOT NULL DEFAULT 0,         -- 展示/展开顺序
    metadata           JSONB,
    created_by         VARCHAR,
    updated_by         VARCHAR,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    CONSTRAINT uq_base_vaccination_types_code UNIQUE (code),
    CONSTRAINT chk_base_vaccination_types_dose CHECK (dose_count >= 1),
    CONSTRAINT chk_base_vaccination_types_months CHECK (jsonb_typeof(recommended_months) = 'array')
);

COMMENT ON TABLE healthcare.base_vaccination_types IS '基础数据·疫苗类型（054 F2）：读库优先、常量回落；库空时 052 接种状态与改造前一致';
COMMENT ON COLUMN healthcare.base_vaccination_types.code IS '疫苗编码，业务唯一键';
COMMENT ON COLUMN healthcare.base_vaccination_types.recommended_months IS '各剂次推荐月龄 JSONB 数组，长度必须等于 dose_count';
COMMENT ON COLUMN healthcare.base_vaccination_types.status IS '状态中文枚举：启用/停用（应用层白名单，不加 CHECK）';

CREATE INDEX IF NOT EXISTS idx_base_vaccination_types_status
    ON healthcare.base_vaccination_types(status, sort_order, code);

-- 发育量表（内容表；definition 承载领域与条目）
CREATE TABLE healthcare.base_developmental_scales (
    id              VARCHAR(32) PRIMARY KEY,             -- 服务端生成 26 位 ULID
    scale_code      VARCHAR NOT NULL,                    -- 量表编码
    scale_version   VARCHAR NOT NULL,                    -- 量表版本
    name            VARCHAR NOT NULL,                    -- 量表中文名
    age_min_months  INTEGER NOT NULL,                    -- 适用月龄下界（含）
    age_max_months  INTEGER NOT NULL,                    -- 适用月龄上界（含）
    definition      JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {domains:[...], items:[...]}
    status          VARCHAR NOT NULL DEFAULT '启用',       -- 启用/停用（应用层白名单）
    metadata        JSONB,
    created_by      VARCHAR,
    updated_by      VARCHAR,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT uq_base_developmental_scales_code_version UNIQUE (scale_code, scale_version),
    CONSTRAINT chk_base_developmental_scales_age
        CHECK (age_min_months >= 0 AND age_max_months >= age_min_months),
    CONSTRAINT chk_base_developmental_scales_definition CHECK (jsonb_typeof(definition) = 'object')
);

COMMENT ON TABLE healthcare.base_developmental_scales IS '基础数据·发育量表（054 F2）：读库优先、常量回落；库空时 053 筛查判读与改造前一致';
COMMENT ON COLUMN healthcare.base_developmental_scales.definition IS '量表定义 JSONB：{domains:[{code,name}],items:[{code,domain,prompt,min_months,max_months,options:[{value,label}]}]}';
COMMENT ON COLUMN healthcare.base_developmental_scales.status IS '状态中文枚举：启用/停用（应用层白名单，不加 CHECK）';

CREATE INDEX IF NOT EXISTS idx_base_developmental_scales_status
    ON healthcare.base_developmental_scales(status, scale_code, scale_version);
