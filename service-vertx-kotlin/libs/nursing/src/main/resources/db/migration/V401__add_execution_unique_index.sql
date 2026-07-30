-- =====================================================
-- 护理模块 — 执行记录幂等性约束
-- 确保相同任务 + 相同计划时间不会重复生成执行记录
-- PostgreSQL 唯一索引将 NULL planned_time 视为不同值，
-- 因此只约束非空的 (task_id, planned_time) 组合
-- =====================================================

-- 清理已有的非空 planned_time 重复记录（保留最早的一条）
DELETE FROM nursing.nursing_task_executions
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY task_id, planned_time
                   ORDER BY created_at ASC
               ) AS rn
        FROM nursing.nursing_task_executions
        WHERE planned_time IS NOT NULL
    ) AS duplicates
    WHERE duplicates.rn > 1
);

-- 添加唯一索引以保证后续幂等写入
CREATE UNIQUE INDEX IF NOT EXISTS idx_exec_task_planned
    ON nursing.nursing_task_executions(task_id, planned_time);

-- 今日工作台查询索引：按 planned_time 日期范围过滤
CREATE INDEX IF NOT EXISTS idx_exec_planned_time
    ON nursing.nursing_task_executions(planned_time);

-- 组合过滤索引：按状态 + planned_time（高频组合）
CREATE INDEX IF NOT EXISTS idx_exec_status_planned
    ON nursing.nursing_task_executions(status, planned_time);
