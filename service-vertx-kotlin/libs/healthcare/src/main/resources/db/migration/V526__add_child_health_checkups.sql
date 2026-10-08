-- =====================================================
-- Healthcare — 儿保体检记录（V526，052 P1）
--
-- 背景：既有 `health_checkups` 以 `checkup_year` 唯一索引表达「机构年度批次」，
-- 与儿童 0–3 岁高频保健体检（满月/3/6/9/12/18/24/36 月）语义冲突。
-- 052 D1 决定**单独建表**，年度体检管理保持现状（儿童仍可被纳入批次名单）。
--
-- 口径：
--   1. 一次保健体检一行，按儿童 + 日期；`checkup_type`/`conclusion` 由应用层白名单校验
--      （沿用 V500/V508/V525「枚举由应用层管控、不加 CHECK」范式）。
--   2. `age_months` 由服务端按 `patients.birth_date` 与 `checkup_date` 派生落库，
--      口径与前端 `lib/age.ts` 的 `formatAge` 日历差一致（不足整月不进位）。
--   3. `items` 为检查项与结果的自由结构（[{item,result,conclusion}]），结构随 UI 演化，
--      不设列级约束。
--   4. `operator` 一律取认证主体，客户端不得提交。
--   5. 不重跑 jOOQ codegen：新表一律以 DSL.table/DSL.field 按名引用（与 V523–V525 一致）。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE child_health_checkups (
    id                VARCHAR(32) PRIMARY KEY,
    patient_id        VARCHAR(32) NOT NULL REFERENCES patients(id),
    checkup_date      DATE NOT NULL,                            -- 体检日期
    checkup_type      VARCHAR NOT NULL,                         -- 满月/3月/6月/9月/12月/18月/24月/3岁/常规
    age_months        INTEGER,                                  -- 体检时月龄，服务端派生
    items             JSONB DEFAULT '[]',                       -- 检查项与结果 [{item,result,conclusion}]
    conclusion        VARCHAR,                                  -- 正常 / 异常 / 需转诊
    guidance          TEXT,                                     -- 本次指导
    next_checkup_date DATE,                                     -- 建议下次体检日期
    operator          VARCHAR NOT NULL,                         -- 操作人（认证主体），客户端不得提交
    remark            TEXT,
    metadata          JSONB,
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE child_health_checkups IS '儿保体检记录：按儿童、按月龄的保健体检（052 P1，独立于年度批次 health_checkups）';
COMMENT ON COLUMN child_health_checkups.checkup_type IS '体检类型中文枚举：满月/3月/6月/9月/12月/18月/24月/3岁/常规（应用层白名单）';
COMMENT ON COLUMN child_health_checkups.age_months IS '体检时月龄，由服务端按 birth_date 与 checkup_date 日历差派生，客户端不得提交';
COMMENT ON COLUMN child_health_checkups.items IS '检查项与结果 [{item,result,conclusion}]，结构随 UI 演化';
COMMENT ON COLUMN child_health_checkups.conclusion IS '结论中文枚举：正常/异常/需转诊（应用层白名单）';
COMMENT ON COLUMN child_health_checkups.operator IS '操作人，来自认证主体，客户端不得提交';

CREATE INDEX IF NOT EXISTS idx_child_health_checkups_patient ON child_health_checkups(patient_id, checkup_date DESC);
CREATE INDEX IF NOT EXISTS idx_child_health_checkups_conclusion ON child_health_checkups(patient_id, conclusion);
