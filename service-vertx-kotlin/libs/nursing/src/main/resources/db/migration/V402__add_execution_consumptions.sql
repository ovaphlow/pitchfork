-- =====================================================
-- 护理模块 — 执行耗材关联表
-- 权威的一对多耗材明细来源
-- =====================================================

CREATE TABLE IF NOT EXISTS nursing_task_execution_consumptions
(
    id                         VARCHAR(32) PRIMARY KEY,
    task_execution_id          VARCHAR(32) NOT NULL REFERENCES nursing.nursing_task_executions(id) ON DELETE CASCADE,
    stock_operation_detail_id  VARCHAR(32) NOT NULL,
    stock_id                   VARCHAR(32) NOT NULL,
    material_id                VARCHAR(32) NOT NULL,
    lot_id                     VARCHAR(32),
    warehouse                  VARCHAR     NOT NULL,
    quantity                   NUMERIC(15,4) NOT NULL CHECK (quantity > 0),
    unit                       VARCHAR     NOT NULL CHECK (unit IN ('PACKAGE', 'SPLIT')),
    split_quantity             NUMERIC(15,4) CHECK (split_quantity IS NULL OR split_quantity > 0),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 每条库存操作明细只能被一条执行耗材关联引用
CREATE UNIQUE INDEX IF NOT EXISTS idx_exec_consumption_detail
    ON nursing_task_execution_consumptions(stock_operation_detail_id);

-- 同一执行 + 同一明细组合唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_exec_consumption_exec_detail
    ON nursing_task_execution_consumptions(task_execution_id, stock_operation_detail_id);

-- 按执行查询索引
CREATE INDEX IF NOT EXISTS idx_exec_consumption_exec
    ON nursing_task_execution_consumptions(task_execution_id);
