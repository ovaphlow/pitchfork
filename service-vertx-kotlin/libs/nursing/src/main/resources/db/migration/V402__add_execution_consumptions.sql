-- =====================================================
-- 护理模块 — 执行耗材关联表
-- 权威的一对多耗材明细来源
-- 016 单一基础单位模型：quantity/unit/unit_cost/total_cost 均为基础数量、
-- 基础单位与每基础单位成本的不可变快照，与库存操作明细同事务写入。
-- =====================================================

SET search_path TO nursing;

CREATE TABLE IF NOT EXISTS nursing_task_execution_consumptions
(
    id                         VARCHAR(32) PRIMARY KEY,
    task_execution_id          VARCHAR(32) NOT NULL REFERENCES nursing.nursing_task_executions(id) ON DELETE CASCADE,
    stock_operation_detail_id  VARCHAR(32) NOT NULL,
    stock_id                   VARCHAR(32) NOT NULL,
    material_id                VARCHAR(32) NOT NULL,
    lot_id                     VARCHAR(32),
    warehouse                  VARCHAR     NOT NULL,
    quantity                   NUMERIC(20,6) NOT NULL CHECK (quantity > 0),
    unit                       VARCHAR     NOT NULL,
    unit_cost                  NUMERIC(24,8) NOT NULL CHECK (unit_cost >= 0),
    total_cost                 NUMERIC(24,8) NOT NULL CHECK (total_cost >= 0),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 每条库存操作明细只能被一条执行耗材关联引用
CREATE UNIQUE INDEX IF NOT EXISTS idx_exec_consumption_detail
    ON nursing.nursing_task_execution_consumptions(stock_operation_detail_id);

-- 同一执行 + 同一明细组合唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_exec_consumption_exec_detail
    ON nursing.nursing_task_execution_consumptions(task_execution_id, stock_operation_detail_id);

-- 按执行查询索引
CREATE INDEX IF NOT EXISTS idx_exec_consumption_exec
    ON nursing.nursing_task_execution_consumptions(task_execution_id);
