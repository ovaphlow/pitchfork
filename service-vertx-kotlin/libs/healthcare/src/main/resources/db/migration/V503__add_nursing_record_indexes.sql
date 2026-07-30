-- =====================================================
-- Healthcare — NURSING_RECORD 索引
-- 支持护理记录查询和唯一性约束
-- =====================================================

-- 1. 部分唯一索引：保证同一 task_execution_id 只有一条原始 EXECUTION 记录
--    仅包含 record_type = 'NURSING_RECORD' 且 metadata->>'record_kind' = 'EXECUTION'
--    且 task_execution_id 非空的行
CREATE UNIQUE INDEX IF NOT EXISTS idx_nursing_record_execution
    ON healthcare.medical_records (
        ((metadata ->> 'task_execution_id'::text))
    )
    WHERE record_type = 'NURSING_RECORD'
      AND metadata ->> 'record_kind' = 'EXECUTION'
      AND metadata ->> 'task_execution_id' IS NOT NULL;

-- 2. 复合查询索引：按 record_type、encounter_id、record_date DESC 支持时间线筛选
CREATE INDEX IF NOT EXISTS idx_medical_records_nursing_query
    ON healthcare.medical_records (record_type, encounter_id, record_date DESC);
