-- =====================================================
-- 054 D2 儿科用药规则库
-- 规则按 material_id + 年龄/体重区间给出单次剂量、频次上限、最大日剂量与单位，
-- 由 libs/pharmacy 纯引擎 PediatricDoseCalculator（054 D1）消费。
--
-- material_id 对药品目录（public.materials）为**跨 schema 弱关联**：不加外键，
-- 由 PediatricDoseRuleService 在写入时校验存在性并落 material_name 快照
-- （054 §3 De；AGENTS.md 要求跨 schema 显式限定）。
-- 只使用 Pharmacy V300+ 号段（V304），不触碰 V300–V303 既有表。
-- =====================================================
SET search_path TO pharmacy, public;

CREATE TABLE pharmacy_pediatric_dose_rules (
    id               VARCHAR(32) PRIMARY KEY,        -- 服务端生成 26 位 ULID
    material_id      VARCHAR(32) NOT NULL,           -- 弱关联 public.materials(id)，无 FK
    material_name    VARCHAR NOT NULL,               -- 写入时 material 名称快照
    age_min_months   INTEGER,                        -- 月龄下界（闭区间），NULL 表示不约束
    age_max_months   INTEGER,                        -- 月龄上界（闭区间），NULL 表示不约束
    weight_min_kg    NUMERIC(20,6),                  -- 体重下界（kg，闭区间）
    weight_max_kg    NUMERIC(20,6),                  -- 体重上界（kg，闭区间）
    dose_per_kg      NUMERIC(20,6),                  -- 按体重折算：单位/kg/次
    fixed_dose       NUMERIC(20,6),                  -- 固定单次剂量（与 dose_per_kg 二选一）
    single_dose_max  NUMERIC(20,6),                  -- 单次上限（可选提示位）
    daily_dose_max   NUMERIC(20,6),                  -- 日总量上限（可选提示位）
    doses_per_day    INTEGER,                        -- 频次上限；NULL 由引擎按每日 1 次处理
    unit             VARCHAR NOT NULL,               -- 剂量单位（mg / mL 等）
    status           VARCHAR NOT NULL DEFAULT '启用', -- 054 §5.8 中文枚举
    note             VARCHAR,
    created_by       VARCHAR,                        -- 认证主体（ctx["userId"]）
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT chk_pediatric_dose_rules_status
        CHECK (status IN ('启用', '停用')),
    CONSTRAINT chk_pediatric_dose_rules_dose_defined
        CHECK (dose_per_kg IS NOT NULL OR fixed_dose IS NOT NULL),
    CONSTRAINT chk_pediatric_dose_rules_age_non_negative
        CHECK ((age_min_months IS NULL OR age_min_months >= 0)
            AND (age_max_months IS NULL OR age_max_months >= 0)),
    CONSTRAINT chk_pediatric_dose_rules_age_band
        CHECK (age_min_months IS NULL OR age_max_months IS NULL OR age_min_months <= age_max_months),
    CONSTRAINT chk_pediatric_dose_rules_weight_positive
        CHECK ((weight_min_kg IS NULL OR weight_min_kg > 0)
            AND (weight_max_kg IS NULL OR weight_max_kg > 0)),
    CONSTRAINT chk_pediatric_dose_rules_weight_band
        CHECK (weight_min_kg IS NULL OR weight_max_kg IS NULL OR weight_min_kg <= weight_max_kg),
    CONSTRAINT chk_pediatric_dose_rules_dose_positive
        CHECK ((dose_per_kg IS NULL OR dose_per_kg > 0)
            AND (fixed_dose IS NULL OR fixed_dose > 0)
            AND (single_dose_max IS NULL OR single_dose_max > 0)
            AND (daily_dose_max IS NULL OR daily_dose_max > 0)),
    CONSTRAINT chk_pediatric_dose_rules_frequency_positive
        CHECK (doses_per_day IS NULL OR doses_per_day > 0)
);

-- 按药品 + 状态匹配（计算入口的唯一查询路径）
CREATE INDEX idx_pediatric_dose_rules_material_status
    ON pharmacy_pediatric_dose_rules(material_id, status);
