-- =====================================================
-- Healthcare — 儿保成长测量（V527，052 P2）
--
-- 背景：`vital_sign_records.type` 的 CHECK 无 HEIGHT/HEAD_CIRCUMFERENCE，且异常按成人
-- 固定阈值判定、WEIGHT 恒不判异常，与儿保「按 WHO 百分位评价」口径不同。
-- 052 D2 决定**单独建表**：一次测量会话一行，身高/体重/头围可空。
-- 052 D3：后端只存原始测量与落库时的评价快照，百分位曲线由前端纯函数绘制。
--
-- 口径：
--   1. 一次测量会话一行，至少一项非空由应用层校验（不加 CHECK）。
--   2. `age_days` 由服务端按 `patients.birth_date` 与 `measured_at` 派生落库。
--   3. `percentile_snapshot` 为落库时的评价快照（{height:{p,z},weight:{...}}），可空：
--      WHO LMS 常量在前端 `lib/growth.ts`，服务端只做形状校验与透传，不复制参考数据。
--   4. 体温仍写 `vital_sign_records`（052 D2/裁定 7）：儿保测量页一次提交写两表。
--   5. `operator` 一律取认证主体，客户端不得提交；删除为软删除（deleted_at）。
--   6. 不重跑 jOOQ codegen：新表一律以 DSL.table/DSL.field 按名引用。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE child_growth_measurements (
    id                    VARCHAR(32) PRIMARY KEY,
    patient_id            VARCHAR(32) NOT NULL REFERENCES patients(id),
    measured_at           TIMESTAMPTZ NOT NULL DEFAULT now(),   -- 测量时间
    age_days              INTEGER,                              -- 测量时天龄，服务端派生
    height_cm             NUMERIC,                              -- 身高/身长（cm），可空
    weight_kg             NUMERIC,                              -- 体重（kg），可空
    head_circumference_cm NUMERIC,                              -- 头围（cm），可空
    percentile_snapshot   JSONB,                                -- 落库时评价快照 {height:{p,z},...}，可空
    operator              VARCHAR NOT NULL,                     -- 操作人（认证主体），客户端不得提交
    note                  TEXT,
    metadata              JSONB,
    deleted_at            TIMESTAMPTZ,                          -- 软删除时间，非空表示已作废
    created_at            TIMESTAMPTZ DEFAULT now(),
    updated_at            TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE child_growth_measurements IS '儿保成长测量：一次会话一行（身高/体重/头围可空），052 P2；体温仍写 vital_sign_records';
COMMENT ON COLUMN child_growth_measurements.age_days IS '测量时天龄，由服务端按 birth_date 与 measured_at 派生，客户端不得提交';
COMMENT ON COLUMN child_growth_measurements.percentile_snapshot IS '落库时评价快照 {height:{p,z},weight:{p,z},head:{p,z}}；WHO LMS 常量在前端 lib/growth.ts，服务端仅校验形状并透传';
COMMENT ON COLUMN child_growth_measurements.deleted_at IS '软删除时间，非空表示记录已作废（默认查询排除）';

CREATE INDEX IF NOT EXISTS idx_child_growth_measurements_patient ON child_growth_measurements(patient_id, measured_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_growth_measurements_patient_age ON child_growth_measurements(patient_id, age_days);
