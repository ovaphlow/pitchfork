-- =====================================================
-- 054 B2 — 儿童康复训练执行（会话）记录
-- Schema: nursing；号段 V400–V499（054 占用 V410）
--
-- 本表是「是否完成训练」的**唯一事实源**（054 BL7 → L7 裁定）：
--   - 儿童康复的训练执行**不复用** `nursing_task_executions` 打卡链
--     （那条链承载养老护理任务与给药，口径/权限/统计口径都不同）；
--   - 进度汇总（打卡次数 / 完成天数 / 平均完成度）一律由本表读时聚合。
--
-- 一次训练 = 一行（append-only 台账）：同一计划同一天允许多次训练，
-- 故不设唯一约束；`completion_ratio` 为该次训练的完成度（0–1）。
-- `patient_id` 不冗余落库，由 `plan_id → nursing_plans → nursing_service_periods` 派生
-- （与 B1 的 `nursing_plans` 不加 `patient_id` 同口径）。
-- =====================================================

CREATE TABLE nursing.nursing_child_rehab_sessions (
    id                VARCHAR(32) PRIMARY KEY,
    plan_id           VARCHAR(32) NOT NULL REFERENCES nursing.nursing_plans(id) ON DELETE CASCADE,
    session_date      DATE NOT NULL,
    completion_ratio  NUMERIC(5,4) NOT NULL DEFAULT 1,   -- 0–1，应用层白名单校验
    performance       VARCHAR,                            -- 表现描述（自由文本，§5.8 未冻结枚举）
    note              TEXT,
    operator          VARCHAR NOT NULL,                   -- 执行人（认证主体，042 口径）
    metadata          JSONB,
    created_at        TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_child_rehab_sessions IS
    '儿童康复训练执行台账（054 B2）；「是否完成训练」的唯一事实源，不复用 nursing_task_executions';

CREATE INDEX IF NOT EXISTS idx_child_rehab_sessions_plan
    ON nursing.nursing_child_rehab_sessions (plan_id, session_date);
CREATE INDEX IF NOT EXISTS idx_child_rehab_sessions_date
    ON nursing.nursing_child_rehab_sessions (session_date);
