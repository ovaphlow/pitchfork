-- =====================================================
-- 054 A1 — 每餐膳食摄入记录 + 菜品营养/过敏原字段
-- Schema: dining；号段 V600–V699（054 占用 V601，见 docs/plans/054...md §3 Di/L1）
--
-- 1) dining_dishes 追加 nutrition / allergens：
--    V600 的菜品库只有 category/meal_times/diet_tags，没有任何营养列，
--    「营养分析」没有数据源（054 §2.2 已核实）。两列均为 JSONB，可空/默认空数组，
--    不改既有列、不删历史数据。
--    nutrition 形状（每 basis_g 克的成分快照，basis_g 缺省按 100 处理）：
--      {"basis_g":100,"energy_kcal":116,"protein_g":2.6,"fat_g":0.3,
--       "carb_g":25.9,"sodium_mg":2.5}
--    allergens 为字符串数组，与 dining_diet_profiles.allergies（V600:20）同口径；
--    054 复用既有列，不新建过敏主数据表（Lead 裁定 L2 / §11.2 Q7）。
--
-- 2) dining_meal_intake_records：同一儿童 + 同一日期 + 同一餐次唯一，
--    重复提交为覆盖更新（幂等，验收 §8.1）。
--    energy_kcal/protein_g/fat_g/carb_g/sodium_mg 为服务端按
--    「食物项份量 × 菜品营养 × 摄入比例」汇总落库的固定口径（客户端不得提交）。
-- =====================================================

ALTER TABLE dining.dining_dishes
    ADD COLUMN IF NOT EXISTS nutrition JSONB,
    ADD COLUMN IF NOT EXISTS allergens JSONB DEFAULT '[]';

COMMENT ON COLUMN dining.dining_dishes.nutrition IS
    '每 basis_g 克营养成分 {"basis_g","energy_kcal","protein_g","fat_g","carb_g","sodium_mg"}';
COMMENT ON COLUMN dining.dining_dishes.allergens IS
    '过敏原标记（字符串数组，与 dining_diet_profiles.allergies 同口径）';

CREATE TABLE dining.dining_meal_intake_records (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    roster_item_id  VARCHAR(32) REFERENCES dining.dining_roster_items(id) ON DELETE SET NULL,
    intake_date     DATE NOT NULL,
    meal_time       VARCHAR NOT NULL,                  -- 早餐/午餐/晚餐/加餐（应用层白名单）
    items           JSONB NOT NULL DEFAULT '[]',       -- [{"food_name","amount_g","note"}]
    intake_ratio    NUMERIC(5,4) NOT NULL DEFAULT 1,   -- 摄入比例 0–1
    energy_kcal     NUMERIC(10,2) NOT NULL DEFAULT 0,  -- 服务端汇总，客户端提交即忽略
    protein_g       NUMERIC(10,2) NOT NULL DEFAULT 0,
    fat_g           NUMERIC(10,2) NOT NULL DEFAULT 0,
    carb_g          NUMERIC(10,2) NOT NULL DEFAULT 0,
    sodium_mg       NUMERIC(10,2) NOT NULL DEFAULT 0,
    operator        VARCHAR NOT NULL,                  -- 认证主体（042 口径）
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- 同一儿童同一餐次唯一：重复提交覆盖更新（幂等），不是新增一行
CREATE UNIQUE INDEX uq_dining_meal_intake_records_meal
    ON dining.dining_meal_intake_records (patient_id, intake_date, meal_time);
CREATE INDEX idx_dining_meal_intake_records_patient_date
    ON dining.dining_meal_intake_records (patient_id, intake_date);
CREATE INDEX idx_dining_meal_intake_records_date
    ON dining.dining_meal_intake_records (intake_date);
