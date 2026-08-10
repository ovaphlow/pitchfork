-- =====================================================
-- Healthcare — 慢病登记档案 (Chronic Disease Registrations)
-- 养老方向：慢病登记 + 病程记录，与随访联动（慢病随访计划自动生成）
-- 设计原则：
--   1. 患者级长期档案：跨入住周期稳定存在，encounter_id 仅为登记时的
--      活动入住锚点（复用 V508 归属强校验语义），出院再入住不丢失档案
--   2. 与 diagnoses 并存：diagnoses 表达"本次入住的诊断"（encounter 维度），
--      本表表达"长期管理档案"（患者维度），经 metadata.diagnosis_id 可选关联
--   3. 状态机由应用层管控：管理中 / 已缓解 / 已停管（软停管，不物理删除）
--   4. 同一患者同一病种仅允许一条"管理中"档案（部分唯一索引兜底防重复建档）
--   5. 随访频率为中文枚举：每月/每两月/每季度/每半年/每年；
--      病种默认频率由服务端常量表提供，metadata 可覆盖
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE chronic_disease_registrations (
    id                  VARCHAR(32) PRIMARY KEY,
    patient_id          VARCHAR(32) NOT NULL REFERENCES patients(id),
    encounter_id        VARCHAR(32) NOT NULL REFERENCES encounters(id), -- 登记时的活动入住（锚点）
    disease_name        VARCHAR NOT NULL,                               -- 中文病种，如 高血压
    icd_code            VARCHAR,                                        -- ICD 编码（可选）
    confirmed_date      DATE NOT NULL,                                  -- 确诊日期
    control_status      VARCHAR NOT NULL DEFAULT '良好',                 -- 控制状态：良好/一般/较差/未控制
    followup_frequency  VARCHAR NOT NULL,                               -- 随访频率：每月/每两月/每季度/每半年/每年
    physician           VARCHAR,                                        -- 责任医生
    remark              TEXT,
    status              VARCHAR NOT NULL DEFAULT '管理中',               -- 管理中/已缓解/已停管
    metadata            JSONB,
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE chronic_disease_registrations IS '慢病登记档案：患者级、跨入住周期的长期管理档案（养老方向）';
COMMENT ON COLUMN chronic_disease_registrations.patient_id IS '患者（老人），跨入住长期锚定';
COMMENT ON COLUMN chronic_disease_registrations.encounter_id IS '登记时的活动入住锚点，仅用于归属校验';
COMMENT ON COLUMN chronic_disease_registrations.control_status IS '控制状态，中文枚举：良好/一般/较差/未控制';
COMMENT ON COLUMN chronic_disease_registrations.followup_frequency IS '随访频率，中文枚举：每月/每两月/每季度/每半年/每年';
COMMENT ON COLUMN chronic_disease_registrations.status IS '档案状态，中文枚举：管理中/已缓解/已停管（软停管）';
COMMENT ON COLUMN chronic_disease_registrations.metadata IS '扩展元数据；可含 diagnosis_id 关联来源诊断、followup_frequency 覆盖默认频率';

-- 同一患者同一病种仅一条"管理中"档案（部分唯一索引，防并发重复建档）
CREATE UNIQUE INDEX IF NOT EXISTS uq_chronic_disease_active
    ON chronic_disease_registrations(patient_id, disease_name)
    WHERE status = '管理中';

CREATE INDEX IF NOT EXISTS idx_chronic_disease_patient ON chronic_disease_registrations(patient_id);
CREATE INDEX IF NOT EXISTS idx_chronic_disease_encounter ON chronic_disease_registrations(encounter_id);
CREATE INDEX IF NOT EXISTS idx_chronic_disease_status ON chronic_disease_registrations(status);
