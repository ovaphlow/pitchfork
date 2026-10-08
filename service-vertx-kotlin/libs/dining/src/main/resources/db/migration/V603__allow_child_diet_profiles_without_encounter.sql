-- =====================================================
-- 054 A4b — 儿保儿童饮食档案（无入住）与过敏原可达
-- Schema: dining；号段 V600–V699（054 占用 V603）
--
-- 背景（054 rev4 A4b）：`dining_diet_profiles.encounter_id` 原为 NOT NULL（V600:18），
-- 且服务层要求 ACTIVE 的 ELDERLY_CARE 入住 → 儿保儿童没有入住周期，**无法建档**，
-- 于是 `PUT /diet-profiles/:id/allergens`（营养师优先功能 #2 过敏原标记）在儿保域不可达。
--
-- 变更：仅放开 `encounter_id` 的可空性。
--   - 外键 `REFERENCES healthcare.encounters(id)` 保留：NULL 不参与外键校验，
--     非空值仍必须指向存在的入住记录。
--   - 既有部分唯一索引 `uq_dining_diet_profiles_active_patient (patient_id) WHERE status='启用'`
--     **不变**：同一长者仍只允许一份启用档案。
--   - 「非儿童必须有 ACTIVE 的 ELDERLY_CARE 入住」是**服务层业务规则**（跨表条件，
--     CHECK 无法表达），由 `DietProfileService` 按 `healthcare.patients.person_type` 分流执行；
--     儿童允许 `encounter_id IS NULL`。
-- =====================================================

ALTER TABLE dining.dining_diet_profiles
    ALTER COLUMN encounter_id DROP NOT NULL;

COMMENT ON COLUMN dining.dining_diet_profiles.encounter_id IS
    '关联入住周期；儿童（person_type=儿童）允许为空（无入住），非儿童仍要求 ACTIVE 的 ELDERLY_CARE 入住（服务层校验）';
