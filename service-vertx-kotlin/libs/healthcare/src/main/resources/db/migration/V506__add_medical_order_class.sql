-- 医嘱持续周期维度：LONG_TERM(长期) / TEMPORARY(临时)
-- 历史 008 医嘱允许 NULL（不擅自猜测），新建医嘱必须由应用层明确传入
ALTER TABLE healthcare.medical_orders ADD COLUMN order_class VARCHAR;

COMMENT ON COLUMN healthcare.medical_orders.order_class IS '医嘱持续周期: LONG_TERM(长期) / TEMPORARY(临时)，历史医嘱可为 NULL';
