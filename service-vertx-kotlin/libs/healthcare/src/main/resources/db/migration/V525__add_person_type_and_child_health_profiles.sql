-- =====================================================
-- Healthcare — 患者域标识与儿保档案（V525）
--
-- 背景：儿保模式此前只改了前端文案，服务端 patients 是医疗/养老/儿保共用的通用主表，
-- 既没有「域」概念也没有儿保专属字段，导致 1950/1965 年生的既有患者出现在儿童档案
-- 列表里，儿保录入表单与长者完全一样。
--
-- 本迁移新增「域」列与 1:1 儿保子表，不回填历史数据、不删/改既有列：
--   patients.person_type  患者域：居民 / 长者 / 儿童（缺省 居民）
--   child_health_profiles 儿保专属档案（监护人、出生信息、喂养、预防接种、备注）
--
-- 口径：
--   1. person_type 取值与前端 DOMAIN_ENTITY.person 对齐，由应用层白名单校验
--      （沿用 V500/V508「枚举由应用层管控、不加 CHECK」范式）。
--   2. 既有患者默认「居民」，自然退出 person_type=儿童 的儿保列表，无需回填；
--      医疗/养老列表保持不过滤（现状）。
--   3. 儿童建档要求 birth_date 非空由服务端校验；本迁移不设年龄上限硬约束。
--   4. child_health_profiles.patient_id 唯一，保证患者与儿保档案 1:1。
--   5. vaccination_records 为结构化接种记录 [{vaccine,dose,date,facility}]，缺省 []。
--   6. 不重跑 jOOQ codegen：新列/新表一律以 DSL.field/DSL.table 按名引用
--      （与 V523/V524 一致），故本文件不改动任何生成代码。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

ALTER TABLE healthcare.patients
    ADD COLUMN person_type VARCHAR NOT NULL DEFAULT '居民';
CREATE INDEX idx_patients_person_type ON healthcare.patients(person_type);

CREATE TABLE healthcare.child_health_profiles (
    id                    VARCHAR(32) PRIMARY KEY,
    patient_id            VARCHAR(32) NOT NULL UNIQUE REFERENCES healthcare.patients(id),
    guardian_name         VARCHAR,
    guardian_relationship VARCHAR,
    guardian_phone        VARCHAR,
    birth_weight_g        INTEGER,
    birth_height_mm       INTEGER,
    delivery_mode         VARCHAR,
    feeding_method        VARCHAR,
    vaccination_summary   TEXT,
    vaccination_records   JSONB DEFAULT '[]',
    remark                TEXT,
    metadata              JSONB,
    created_at            TIMESTAMPTZ DEFAULT now(),
    updated_at            TIMESTAMPTZ DEFAULT now()
);

COMMENT ON COLUMN patients.person_type IS '患者域：居民/长者/儿童，缺省居民；应用层白名单校验，不加 CHECK';
COMMENT ON TABLE child_health_profiles IS '儿保专属档案，与 patients 1:1（patient_id 唯一）；仅 person_type=儿童 时持久化';
COMMENT ON COLUMN child_health_profiles.vaccination_records IS '结构化接种记录 [{vaccine,dose,date,facility}]，缺省 []';
