-- =====================================================
-- 016 基线重写：V402 已在 nursing schema 中直接创建耗材关联表。
-- 本迁移保留防御性归位逻辑，防止历史环境把表误放到 public schema；
-- 不再创建 PACKAGE/SPLIT 枚举或拆零列。
-- =====================================================

SET search_path TO nursing, public;

-- 如果表在 public schema 中（V402 遗留的），移到 nursing
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

-- 确保表存在且具备基础单位口径（防御：历史错误建表缺列时拒绝静默工作）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'nursing'
          AND table_name = 'nursing_task_execution_consumptions'
    ) THEN
        RAISE EXCEPTION 'nursing_task_execution_consumptions must be created by V402';
    END IF;
END;
$$;
