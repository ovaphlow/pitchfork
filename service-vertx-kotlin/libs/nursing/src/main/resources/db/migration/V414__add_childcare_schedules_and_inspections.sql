-- =====================================================
-- 054 C2 — 保育员班级日程与环境安全巡查
-- Schema: nursing；号段 V400–V499（054 占用 V414）
--
-- 设计（054 §5.7/§5.8、§11.2 Q2）：
--   - 班级日程**必须是独立表**：`nursing_plans.period_id` 为 NOT NULL（挂服务周期），
--     班级日程与个体服务周期无关，无法复用（§7 C2 行明确）。
--   - **同时段冲突 409** 由**部分唯一索引**兜底（并发下亦成立）：
--     `(class_name, schedule_date, time_slot) WHERE status <> '已取消'`
--     —— 取消（已取消）后该时段释放，可重新排课；草稿/已发布占用时段。
--   - 日程状态（§5.8 冻结）：`草稿 / 已发布 / 已取消`；流转 草稿→{已发布,已取消}、已发布→已取消、已取消 终态。
--   - 巡查隐患闭环（§5.8 冻结）：`待整改 → 已整改 → 已关闭`（严格线性，跳步/终态再流转 4xx）；
--     检查项为 JSONB 数组快照（`item_code` 走常量表，`result ∈ 合格/不合格/不适用`）。
-- =====================================================

CREATE TABLE nursing.nursing_childcare_class_schedules (
    id             VARCHAR(32) PRIMARY KEY,
    class_name     VARCHAR NOT NULL,
    schedule_date  DATE NOT NULL,
    time_slot      VARCHAR NOT NULL,                  -- 固定时段（ChildcareClassScheduleService.TIME_SLOTS）
    activity_name  VARCHAR NOT NULL,
    status         VARCHAR NOT NULL DEFAULT '草稿',    -- 草稿 / 已发布 / 已取消（§5.8）
    operator       VARCHAR NOT NULL,                  -- 排课人（认证主体，042 口径）
    metadata       JSONB,
    created_at     TIMESTAMPTZ DEFAULT now(),
    updated_at     TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_childcare_class_schedules IS
    '保育员班级日程（054 C2，独立表）；同时段冲突由 uq_childcare_class_schedules_slot 兜底为 409';

-- 同时段冲突：同班级 + 同日期 + 同时段，且未取消 → 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uq_childcare_class_schedules_slot
    ON nursing.nursing_childcare_class_schedules (class_name, schedule_date, time_slot)
    WHERE status <> '已取消';
CREATE INDEX IF NOT EXISTS idx_childcare_class_schedules_class_date
    ON nursing.nursing_childcare_class_schedules (class_name, schedule_date);

CREATE TABLE nursing.nursing_childcare_safety_inspections (
    id            VARCHAR(32) PRIMARY KEY,
    area          VARCHAR NOT NULL,                   -- 巡查区域
    inspected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    items         JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{item_code, result, hazard?}]
    status        VARCHAR NOT NULL DEFAULT '待整改',   -- 待整改 / 已整改 / 已关闭（§5.8）
    operator      VARCHAR NOT NULL,                   -- 巡查人（认证主体，042 口径）
    metadata      JSONB,
    created_at    TIMESTAMPTZ DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_childcare_safety_inspections IS
    '保育员环境安全巡查（054 C2）；隐患闭环 待整改→已整改→已关闭，items 为检查项 JSONB 快照';

CREATE INDEX IF NOT EXISTS idx_childcare_safety_inspections_area_time
    ON nursing.nursing_childcare_safety_inspections (area, inspected_at DESC);
CREATE INDEX IF NOT EXISTS idx_childcare_safety_inspections_open
    ON nursing.nursing_childcare_safety_inspections (status) WHERE status <> '已关闭';
