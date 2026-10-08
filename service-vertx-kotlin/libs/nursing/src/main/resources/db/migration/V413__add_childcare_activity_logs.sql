-- =====================================================
-- 054 C1 — 保育员日常活动日志与行为观测记录
-- Schema: nursing；号段 V400–V499（054 占用 V413）
--
-- 定位（054 §5.2 / §11.2 Q2）：活动日志、行为观测是**照护文书**，落 `nursing` schema
-- （`nursing_childcare_*` 前缀），写入复用既有 `nursing:document` 码，**不新增权限码**（L4）。
--
--   - 活动日志：**班级级**台账，`patient_id` 可空（班级集体活动不针对个体儿童）；
--     同一班级同一天可有多条（不同活动），故不设唯一约束。
--   - 行为观测：**儿童级**记录，`patient_id` 必填；append-only（无 updated_at）。
--     `level` 取值见 §5.8 冻结白名单：`一般 / 关注 / 需干预`（应用层校验）。
-- =====================================================

CREATE TABLE nursing.nursing_childcare_activity_logs (
    id                 VARCHAR(32) PRIMARY KEY,
    patient_id         VARCHAR(32),                       -- 可空：班级级活动
    class_name         VARCHAR NOT NULL,
    activity_date      DATE NOT NULL,
    activity_name      VARCHAR NOT NULL,
    participant_count  INT NOT NULL DEFAULT 0,
    note               TEXT,
    operator           VARCHAR NOT NULL,                  -- 记录人（认证主体，042 口径）
    metadata           JSONB,
    created_at         TIMESTAMPTZ DEFAULT now(),
    updated_at         TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_childcare_activity_logs IS
    '保育员日常活动日志（054 C1）；班级级台账，patient_id 可空';

CREATE INDEX IF NOT EXISTS idx_childcare_activity_logs_class_date
    ON nursing.nursing_childcare_activity_logs (class_name, activity_date);
CREATE INDEX IF NOT EXISTS idx_childcare_activity_logs_patient_date
    ON nursing.nursing_childcare_activity_logs (patient_id, activity_date);

CREATE TABLE nursing.nursing_childcare_behavior_observations (
    id           VARCHAR(32) PRIMARY KEY,
    patient_id   VARCHAR(32) NOT NULL,
    observed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    category     VARCHAR NOT NULL,                        -- 观测类别（§5.8 未冻结 → 自由文本）
    description  TEXT NOT NULL,
    level        VARCHAR,                                 -- 一般 / 关注 / 需干预（§5.8 冻结白名单）
    operator     VARCHAR NOT NULL,                        -- 记录人（认证主体，042 口径）
    metadata     JSONB,
    created_at   TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_childcare_behavior_observations IS
    '保育员行为观测记录（054 C1）；儿童级 append-only，level ∈ 一般/关注/需干预';

CREATE INDEX IF NOT EXISTS idx_childcare_behavior_observations_patient
    ON nursing.nursing_childcare_behavior_observations (patient_id, observed_at DESC);
