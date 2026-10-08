-- =====================================================
-- 054 B1 — 儿童康复服务周期（CHILD_REHAB）
-- Schema: nursing；号段 V400–V499（054 占用 V409）
--
-- 1) `nursing_service_periods.service_type` 白名单增加 **CHILD_REHAB**
--    （英文 code，054 §5.8 / N15）：与既有 ELDERLY_CARE / HOME_CARE /
--    COMMUNITY_CARE / HOSPICE 并列。
-- 2) 同儿童**至多一个启用中的儿童康复周期**：部分唯一索引，**必须带
--    service_type 谓词**（R3 架构裁定），否则会影响其它服务类型的重复启用。
-- 3) `encounter_id` 无需改约束：V404 的 `chk_elderly_care_encounter_link`
--    已要求「非 ELDERLY_CARE ⇒ encounter_id IS NULL」，CHILD_REHAB 天然满足
--    （儿童社区/门诊康复无入住周期）。
-- =====================================================

SET search_path TO nursing, public;

-- 1) 服务类型白名单扩展（CHECK 重建与唯一索引同批）
ALTER TABLE nursing_service_periods
    DROP CONSTRAINT nursing_service_periods_service_type_check;

ALTER TABLE nursing_service_periods
    ADD CONSTRAINT nursing_service_periods_service_type_check CHECK (
        service_type IN ('HOME_CARE', 'COMMUNITY_CARE', 'HOSPICE', 'ELDERLY_CARE', 'CHILD_REHAB')
    );

-- 2) 同儿童至多一个 ACTIVE 的儿童康复周期（并发下由 DB 兜底 → 服务层映射 409）
CREATE UNIQUE INDEX IF NOT EXISTS uq_nursing_service_periods_active_child_rehab
    ON nursing_service_periods (patient_id)
    WHERE service_type = 'CHILD_REHAB' AND status = 'ACTIVE';

-- 3) 儿童康复周期按儿童 + 状态查询
CREATE INDEX IF NOT EXISTS idx_period_child_rehab_patient
    ON nursing_service_periods (patient_id, status)
    WHERE service_type = 'CHILD_REHAB';
