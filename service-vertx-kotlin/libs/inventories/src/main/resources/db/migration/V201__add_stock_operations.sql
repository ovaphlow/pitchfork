-- =====================================================
-- 库存模块 - 库存结存、出入库操作单与明细
-- 016 单一基础单位模型：所有数量、锁定量、单位成本和总成本均直接按物资基础单位记账。
-- =====================================================

-- 库存结存表
CREATE TABLE IF NOT EXISTS stocks
(
    id              VARCHAR(32) PRIMARY KEY,
    warehouse       VARCHAR     NOT NULL,
    material_id     VARCHAR(32) NOT NULL REFERENCES materials(id),
    lot_id          VARCHAR(32) REFERENCES lots(id),
    quantity        NUMERIC(20,6) NOT NULL DEFAULT 0,
    locked_quantity NUMERIC(20,6) NOT NULL DEFAULT 0,
    total_cost      NUMERIC(24,8) NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
    CONSTRAINT ck_stocks_quantity_ge_locked
        CHECK (quantity >= locked_quantity AND locked_quantity >= 0),
    last_updated    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 部分唯一索引：无批次时按 warehouse + material_id 唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_stocks_wh_material
    ON stocks(warehouse, material_id)
    WHERE lot_id IS NULL;

-- 部分唯一索引：有批次时按 warehouse + material_id + lot_id 唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_stocks_wh_material_lot
    ON stocks(warehouse, material_id, lot_id)
    WHERE lot_id IS NOT NULL;

-- 库存选择查询索引
CREATE INDEX IF NOT EXISTS idx_stocks_wh_material_query
    ON stocks(warehouse, material_id)
    WHERE quantity > 0;

-- 库存操作单主表
CREATE TABLE IF NOT EXISTS stock_operations
(
    id              VARCHAR(32) PRIMARY KEY,
    order_no        VARCHAR     NOT NULL UNIQUE,
    operation_type  VARCHAR     NOT NULL CHECK (operation_type IN ('INBOUND', 'OUTBOUND')),
    warehouse       VARCHAR     NOT NULL,
    status          VARCHAR     NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at    TIMESTAMPTZ
);

-- 护理耗材出库幂等约束：每个 NURSING_EXECUTION 来源最多一张出库单
-- 仅对 operation_type = 'OUTBOUND' 且 metadata->>'source' = 'NURSING_EXECUTION' 生效
CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_ops_nursing_execution
    ON stock_operations((metadata->>'task_execution_id'))
    WHERE operation_type = 'OUTBOUND' AND metadata->>'source' = 'NURSING_EXECUTION'
      AND metadata->>'task_execution_id' IS NOT NULL;

-- 操作单查询索引
CREATE INDEX IF NOT EXISTS idx_stock_ops_warehouse ON stock_operations(warehouse);
CREATE INDEX IF NOT EXISTS idx_stock_ops_type_status ON stock_operations(operation_type, status);

-- 库存操作明细表
CREATE TABLE IF NOT EXISTS stock_operation_details
(
    id              VARCHAR(32) PRIMARY KEY,
    operation_id    VARCHAR(32) NOT NULL REFERENCES stock_operations(id) ON DELETE CASCADE,
    material_id     VARCHAR(32) NOT NULL REFERENCES materials(id),
    lot_id          VARCHAR(32) REFERENCES lots(id),
    quantity        NUMERIC(20,6) NOT NULL CHECK (quantity > 0),
    unit            VARCHAR     NOT NULL,
    unit_cost       NUMERIC(24,8) NOT NULL CHECK (unit_cost >= 0),
    total_cost      NUMERIC(24,8) NOT NULL CHECK (total_cost >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 明细查询索引
CREATE INDEX IF NOT EXISTS idx_stock_op_details_operation ON stock_operation_details(operation_id);
CREATE INDEX IF NOT EXISTS idx_stock_op_details_material ON stock_operation_details(material_id);
