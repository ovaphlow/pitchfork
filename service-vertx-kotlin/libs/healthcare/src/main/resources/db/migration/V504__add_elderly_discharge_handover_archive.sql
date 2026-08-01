-- =====================================================
-- Healthcare — 养老照护离院交接摘要归档
-- Schema: healthcare
-- =====================================================
-- 为 healthcare.medical_records 的养老离院交接摘要增加最小索引：
--   1. 部分唯一索引：同一照护周期（metadata->>'period_id'）至多一份养老归档摘要，
--      防止并发归档产生第二份文书；唯一竞争后由服务端锁读既有行做幂等比较。
--   2. 按 encounter_id 获取本类摘要复用 V503 的
--      idx_medical_records_nursing_query (record_type, encounter_id, record_date DESC)，
--      不重复创建等价索引。
-- 不新增表、列、跨 schema 外键，不回填历史离院数据。

SET search_path TO healthcare, public;

CREATE UNIQUE INDEX IF NOT EXISTS uq_medical_records_elderly_handover_period
    ON healthcare.medical_records (
        ((metadata ->> 'period_id'::text))
    )
    WHERE record_type = 'DISCHARGE_SUMMARY'
      AND metadata ->> 'is_elderly_discharge_handover' = 'true'
      AND metadata ->> 'period_id' IS NOT NULL;
