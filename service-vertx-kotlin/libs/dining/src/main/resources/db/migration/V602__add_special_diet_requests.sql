-- =====================================================
-- 054 A4/A5 — 特殊膳食申请 + 营养周报快照
-- Schema: dining；号段 V600–V699（054 占用 V602）
--
-- 1) dining_special_diet_requests：营养师对儿童的过敏回避 / 治疗膳食 / 其他
--    特殊膳食申请与审批台账。状态机（应用层白名单，不加 CHECK，沿用 V500/V525 范式）：
--      待审批 → 已批准 / 已驳回 / 已终止；已批准 / 已驳回 → 已终止；已终止为终态。
--    过敏原**复用 dining_diet_profiles.allergies 既有列**，本表只存申请单上的
--    过敏原快照（字符串数组，与 V600:20 同口径），**不新建过敏主数据表**（Lead 裁定 L2）。
--
-- 2) dining_nutrition_weekly_reports：营养周报**快照**（054 §3 Dg：显式生成 + 落库快照，
--    同儿童同周重复生成覆盖；不做后台定时任务）。逐日明细与缺口项存 JSONB，
--    参考值口径版本随快照落库，便于历史周报可追溯。
-- =====================================================

CREATE TABLE dining.dining_special_diet_requests (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    request_type    VARCHAR NOT NULL,                  -- 过敏回避/治疗膳食/其他
    meal_type       VARCHAR NOT NULL,                  -- 早餐/午餐/晚餐/加餐
    allergens       JSONB NOT NULL DEFAULT '[]',       -- 申请单过敏原快照（字符串数组）
    reason          TEXT,
    status          VARCHAR NOT NULL DEFAULT '待审批',  -- 待审批/已批准/已驳回/已终止
    requested_by    VARCHAR NOT NULL,                  -- 申请人（认证主体，042 口径）
    reviewed_by     VARCHAR,                           -- 审批人（认证主体）
    reviewed_at     TIMESTAMPTZ,
    effective_from  DATE,
    effective_to    DATE,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_dining_special_diet_requests_patient
    ON dining.dining_special_diet_requests (patient_id, status);
CREATE INDEX idx_dining_special_diet_requests_status
    ON dining.dining_special_diet_requests (status);

CREATE TABLE dining.dining_nutrition_weekly_reports (
    id                  VARCHAR(32) PRIMARY KEY,
    patient_id          VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    week_start          DATE NOT NULL,                 -- 周一（DiningConstants.weekStartOf 口径）
    week_end            DATE NOT NULL,                 -- 周日
    intake_days         INT NOT NULL DEFAULT 0,        -- 当周有摄入记录的天数
    energy_avg_kcal     NUMERIC(10,2) NOT NULL DEFAULT 0,
    protein_avg_g       NUMERIC(10,2) NOT NULL DEFAULT 0,
    fat_avg_g           NUMERIC(10,2) NOT NULL DEFAULT 0,
    carb_avg_g          NUMERIC(10,2) NOT NULL DEFAULT 0,
    sodium_avg_mg       NUMERIC(10,2) NOT NULL DEFAULT 0,
    conclusion          VARCHAR NOT NULL,              -- 服务端派生（与 A2 同口径）
    gaps                JSONB NOT NULL DEFAULT '[]',   -- 当周缺口项汇总
    daily               JSONB NOT NULL DEFAULT '[]',   -- 逐日快照 [{intake_date,energy_kcal,...}]
    reference_version   VARCHAR,                       -- 参考值常量版本
    generated_by        VARCHAR NOT NULL,              -- 生成人（认证主体）
    metadata            JSONB,
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now()
);
-- 同儿童同周唯一：重复生成为覆盖更新，不产生重复周报
CREATE UNIQUE INDEX uq_dining_nutrition_weekly_reports_week
    ON dining.dining_nutrition_weekly_reports (patient_id, week_start);
CREATE INDEX idx_dining_nutrition_weekly_reports_week_start
    ON dining.dining_nutrition_weekly_reports (week_start);
