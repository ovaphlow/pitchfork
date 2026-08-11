-- =====================================================
-- Healthcare — 生命体征记录 (Vital Sign Records)
-- 养老方向：入住长者的日常健康监测
-- Schema: healthcare
-- 设计原则：
--   1. 以 patients 锚定记录归属，encounter_id 可空（居家/社区无入住周期时
--      不强制关联），提供时校验必须属于该老人
--   2. 体征类型为英文 CHECK 枚举（与 V500+ 英文枚举约定一致），前端展示映射中文
--   3. 异常判定（abnormal）由应用层按内置参考范围计算，MVP 用代码常量，
--      允许通过 metadata 覆盖阈值
--   4. 血压按收缩/舒张两条独立记录建模，保持表结构统一
--   5. 删除采用软删除（deleted_at），养老数据可追溯
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE vital_sign_records (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    encounter_id    VARCHAR(32) REFERENCES encounters(id),   -- 可空：居家/社区场景
    type            VARCHAR NOT NULL CHECK (type IN (
                        'TEMPERATURE', 'PULSE', 'RESPIRATION',
                        'SYSTOLIC_BP', 'DIASTOLIC_BP', 'SPO2',
                        'BLOOD_GLUCOSE', 'WEIGHT'
                    )),
    value           NUMERIC NOT NULL,                        -- 测量数值
    unit            VARCHAR NOT NULL,                         -- 单位: ℃ / 次/分 / mmHg / % / mmol/L / kg
    measured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),       -- 测量时间
    recorded_by     VARCHAR NOT NULL,                         -- 记录人（认证主体），客户端不得提交
    abnormal        BOOLEAN NOT NULL DEFAULT false,           -- 超参考范围标记，服务端计算
    note            TEXT,                                     -- 备注
    metadata        JSONB,                                    -- 扩展字段，可覆盖参考阈值
    deleted_at      TIMESTAMPTZ,                              -- 软删除时间，非空表示已作废
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE vital_sign_records IS '生命体征记录：入住长者日常健康监测（养老方向）';
COMMENT ON COLUMN vital_sign_records.type IS '体征类型英文枚举：TEMPERATURE/PULSE/RESPIRATION/SYSTOLIC_BP/DIASTOLIC_BP/SPO2/BLOOD_GLUCOSE/WEIGHT';
COMMENT ON COLUMN vital_sign_records.abnormal IS '是否超出正常参考范围，由服务端按类型内置阈值计算';
COMMENT ON COLUMN vital_sign_records.metadata IS '扩展元数据；可含 thresholds 覆盖参考范围 {"thresholds":{"TEMPERATURE":{"min":..,"max":..}}}';
COMMENT ON COLUMN vital_sign_records.deleted_at IS '软删除时间，非空表示记录已作废（数据可追溯）';

CREATE INDEX IF NOT EXISTS idx_vital_signs_patient_measured ON vital_sign_records(patient_id, measured_at DESC);
CREATE INDEX IF NOT EXISTS idx_vital_signs_patient_type_measured ON vital_sign_records(patient_id, type, measured_at);
CREATE INDEX IF NOT EXISTS idx_vital_signs_encounter ON vital_sign_records(encounter_id);
