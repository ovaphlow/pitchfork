-- =====================================================
-- V407: 护理执行耗材关联的基础数量与换算快照
--
-- 依据：docs/plans/015.aceso-inventory-base-unit-and-packaging-conversion.md
-- 归属：nursing_task_execution_consumptions 属于 Nursing，故此迁移在
--       libs/nursing 下；不得把该组字段加入 Inventory V202。
-- 原则：
--   * 不改写 V402/V403；表以 nursing schema 为权威（V403 已完成迁移）
--   * 新快照列与关联库存操作明细（public.stock_operation_details）
--     的 V202 快照逐项一致，通过该表回填
--   * 无法匹配或换算的旧耗材记录标记 READ_ONLY，不能被后续任务
--     状态更新覆盖（服务端按 migration_status 只读处理）
-- =====================================================

SET search_path TO nursing, public;

-- 防御：若 V403 未执行导致表仍位于 public schema，先归位（不修改 V402/V403 文件）
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'nursing_task_execution_consumptions'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'nursing'
          AND table_name = 'nursing_task_execution_consumptions'
    ) THEN
        ALTER TABLE public.nursing_task_execution_consumptions
            SET SCHEMA nursing;
    END IF;
END;
$$;

-- =====================================================
-- 1. 新增基础数量与换算快照列
-- =====================================================

ALTER TABLE nursing.nursing_task_execution_consumptions
    ADD COLUMN unit_spec_id VARCHAR(32),
    ADD COLUMN input_quantity NUMERIC(20,6),
    ADD COLUMN input_unit VARCHAR,
    ADD COLUMN conversion_ratio NUMERIC(20,6),
    ADD COLUMN base_quantity NUMERIC(20,6),
    ADD COLUMN base_unit VARCHAR,
    ADD COLUMN input_unit_cost NUMERIC(24,8),
    ADD COLUMN base_unit_cost NUMERIC(24,8),
    ADD COLUMN migration_status VARCHAR NOT NULL DEFAULT 'OK'
        CHECK (migration_status IN ('OK', 'READ_ONLY'));

-- 基础数量查询索引
CREATE INDEX IF NOT EXISTS idx_cc_base_material
    ON nursing.nursing_task_execution_consumptions(material_id, base_quantity)
    WHERE base_quantity IS NOT NULL;

-- =====================================================
-- 2. 回填：从关联的库存操作明细复制不可变换算快照
-- =====================================================

UPDATE nursing.nursing_task_execution_consumptions cc
SET unit_spec_id     = sod.unit_spec_id,
    input_quantity   = sod.input_quantity,
    input_unit       = sod.input_unit,
    conversion_ratio = sod.conversion_ratio,
    base_quantity    = sod.base_quantity,
    base_unit        = sod.base_unit,
    input_unit_cost  = sod.input_unit_cost,
    base_unit_cost   = sod.base_unit_cost,
    migration_status = CASE
                           WHEN sod.base_quantity IS NOT NULL THEN 'OK'
                           ELSE 'READ_ONLY'
                       END
FROM public.stock_operation_details sod
WHERE cc.stock_operation_detail_id = sod.id
  AND sod.base_quantity IS NOT NULL;

-- 无法匹配 / 库存明细缺少快照的旧耗材记录：只读标记，保留历史读取
UPDATE nursing.nursing_task_execution_consumptions
SET migration_status = 'READ_ONLY'
WHERE migration_status = 'OK'
  AND base_quantity IS NULL;
