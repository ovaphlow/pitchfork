-- =====================================================
-- 护理模块 - 养老院入住与照护周期绑定
-- Schema: nursing
-- =====================================================
-- 为 nursing_service_periods 增加 encounter_id：
--   - ELDERLY_CARE 类型周期必须填写 encounter_id，且至多关联一个入住记录
--   - 其它既有服务类型（HOME_CARE / COMMUNITY_CARE / HOSPICE）保留空关联
-- 不回填既有数据，不引入跨 schema 外键；遗留活动养老入住由受控补建接口处理。

SET search_path TO nursing, public;

ALTER TABLE nursing_service_periods
    ADD COLUMN encounter_id VARCHAR(32);

-- 服务类型检查：允许既有 ELDERLY_CARE 代码
ALTER TABLE nursing_service_periods
    DROP CONSTRAINT nursing_service_periods_service_type_check;

ALTER TABLE nursing_service_periods
    ADD CONSTRAINT nursing_service_periods_service_type_check CHECK (
        service_type IN ('HOME_CARE', 'COMMUNITY_CARE', 'HOSPICE', 'ELDERLY_CARE')
    );

-- ELDERLY_CARE 与非空 encounter_id 成对出现；其它服务类型必须保持空关联
ALTER TABLE nursing_service_periods
    ADD CONSTRAINT chk_elderly_care_encounter_link CHECK (
        (service_type = 'ELDERLY_CARE' AND encounter_id IS NOT NULL)
        OR (service_type <> 'ELDERLY_CARE' AND encounter_id IS NULL)
    );

-- 任何非空入住 ID 至多关联一个服务期（并发补建幂等依赖此唯一约束）
CREATE UNIQUE INDEX IF NOT EXISTS uq_nursing_service_periods_encounter_id
    ON nursing_service_periods (encounter_id)
    WHERE encounter_id IS NOT NULL;

-- 按 encounter_id, status 查询的索引（工作台加载所选入住记录）
CREATE INDEX IF NOT EXISTS idx_period_encounter_status
    ON nursing_service_periods (encounter_id, status)
    WHERE encounter_id IS NOT NULL;
