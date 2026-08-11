-- =====================================================
-- Healthcare — 体征异常处理状态 (Vital Sign Review)
-- 养老方向：异常告警闭环（发现 → 复核 → 转诊 → 随访）
-- 设计原则：
--   1. 显式列（与 recorded_by 等审计字段风格一致），MVP 不做独立复核历史表
--   2. review_status 为应用层白名单中文枚举：待复核/已确认/已误报/已转诊
--   3. 复核结论（review_result/note/reviewed_by/reviewed_at）覆盖式更新，
--      复核人一律取认证主体；PATCH 修正导致 abnormal 翻转时由应用层重置
--   4. 转诊复用 followup_plans（慢病随访 + 门诊），计划 metadata 关联体征记录
-- =====================================================

SET search_path TO healthcare, public;

ALTER TABLE vital_sign_records
    ADD COLUMN review_status  VARCHAR NOT NULL DEFAULT '待复核',  -- 待复核/已确认/已误报/已转诊
    ADD COLUMN review_result  VARCHAR,                            -- 复核结论：确认异常/误报（未复核为 NULL）
    ADD COLUMN review_note    TEXT,                               -- 复核备注（≤500 字）
    ADD COLUMN reviewed_by    VARCHAR,                            -- 复核人（认证主体），客户端不得提交
    ADD COLUMN reviewed_at    TIMESTAMPTZ;                        -- 复核时间

COMMENT ON COLUMN vital_sign_records.review_status IS '异常处理状态：待复核/已确认/已误报/已转诊（应用层白名单，客户端不得提交）';
COMMENT ON COLUMN vital_sign_records.review_result IS '复核结论：确认异常/误报（未复核为 NULL）';
COMMENT ON COLUMN vital_sign_records.review_note IS '复核备注（≤500 字）';
COMMENT ON COLUMN vital_sign_records.reviewed_by IS '复核人，来自认证主体，客户端不得提交';
COMMENT ON COLUMN vital_sign_records.reviewed_at IS '复核时间';

CREATE INDEX IF NOT EXISTS idx_vital_signs_abnormal_status
    ON vital_sign_records(abnormal, review_status, measured_at DESC);
