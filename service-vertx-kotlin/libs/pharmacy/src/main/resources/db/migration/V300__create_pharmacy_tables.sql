-- =====================================================
-- 药房模块 - 核心表结构
-- 发药、退药、申领
-- Schema: pharmacy
-- =====================================================

CREATE SCHEMA IF NOT EXISTS pharmacy;
SET search_path TO pharmacy, public;

-- 发药单主表
CREATE TABLE pharmacy_dispenses (
    id              VARCHAR(32) PRIMARY KEY,
    dispense_no     VARCHAR NOT NULL UNIQUE,           -- 发药单号
    patient_id      VARCHAR(32) NOT NULL,              -- 患者/老人ID（外键由 healthcare 模块管理）
    encounter_id    VARCHAR(32),                       -- 就诊/入住ID
    dispense_type   VARCHAR NOT NULL CHECK (dispense_type IN (
                        'OUTPATIENT',       -- 门诊发药
                        'INPATIENT',        -- 住院发药
                        'WARD_BATCH',       -- 病区统领
                        'ELDERLY_ROUTINE'   -- 养老院日常发药
                    )),
    status          VARCHAR DEFAULT 'PENDING' CHECK (status IN (
                        'PENDING',          -- 待处理
                        'REVIEWED',         -- 已审方
                        'DISPENSING',       -- 调配中
                        'DISPENSED',        -- 已发药
                        'CANCELLED'
                    )),
    pharmacist      VARCHAR,                           -- 调配药师/发药人
    reviewer        VARCHAR,                           -- 审方药师/核对人
    metadata        JSONB,                             -- 扩展：病区、特殊标记等
    created_at      TIMESTAMPTZ DEFAULT now(),
    dispensed_at    TIMESTAMPTZ
);

-- 发药明细
CREATE TABLE pharmacy_dispense_items (
    id                          VARCHAR(32) PRIMARY KEY,
    dispense_id                 VARCHAR(32) NOT NULL REFERENCES pharmacy_dispenses(id),
    order_item_id               VARCHAR(32),           -- 医嘱明细ID（弱关联）
    order_execution_id          VARCHAR(32),           -- 医嘱执行记录ID
    material_id                 VARCHAR(32) NOT NULL,  -- 药品ID（弱关联）
    lot_id                      VARCHAR(32),           -- 批次ID
    prescribed_quantity         NUMERIC(15,4),         -- 处方数量（包装单位）
    dispensed_quantity          NUMERIC(15,4),         -- 实发数量
    unit                        VARCHAR DEFAULT 'PACKAGE' CHECK (unit IN ('PACKAGE','SPLIT')),
    split_quantity              NUMERIC(15,4),         -- 拆零数量
    stock_operation_detail_id   VARCHAR(32),           -- 关联库存出库明细ID（弱关联）
    unit_cost                   NUMERIC(15,4),
    total_cost                  NUMERIC(18,4),
    metadata                    JSONB
);

-- 退药单主表
CREATE TABLE pharmacy_returns (
    id                  VARCHAR(32) PRIMARY KEY,
    return_no           VARCHAR NOT NULL UNIQUE,
    original_dispense_id VARCHAR(32) NOT NULL REFERENCES pharmacy_dispenses(id),
    patient_id          VARCHAR(32) NOT NULL,
    return_reason       VARCHAR,
    status              VARCHAR DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','CANCELLED')),
    operator            VARCHAR,                        -- 操作人
    metadata            JSONB,
    created_at          TIMESTAMPTZ DEFAULT now(),
    confirmed_at        TIMESTAMPTZ
);

-- 退药明细
CREATE TABLE pharmacy_return_items (
    id                          VARCHAR(32) PRIMARY KEY,
    return_id                   VARCHAR(32) NOT NULL REFERENCES pharmacy_returns(id),
    dispense_item_id            VARCHAR(32) NOT NULL REFERENCES pharmacy_dispense_items(id),
    quantity                    NUMERIC(15,4) NOT NULL,   -- 退回数量（包装单位）
    stock_operation_detail_id   VARCHAR(32),              -- 关联库存入库明细ID
    unit_cost                   NUMERIC(15,4),
    total_cost                  NUMERIC(18,4),
    metadata                    JSONB
);

-- 药品申领单主表（科室/护理站向药房请领）
CREATE TABLE pharmacy_requisitions (
    id              VARCHAR(32) PRIMARY KEY,
    requisition_no  VARCHAR NOT NULL UNIQUE,
    warehouse       VARCHAR NOT NULL,                    -- 请领的药房仓库名称
    requester       VARCHAR,                             -- 申领人
    department      VARCHAR,                             -- 申领科室/护理站
    status          VARCHAR DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','DISPENSED','CANCELLED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    dispensed_at    TIMESTAMPTZ
);

-- 申领明细
CREATE TABLE pharmacy_requisition_items (
    id                          VARCHAR(32) PRIMARY KEY,
    requisition_id              VARCHAR(32) NOT NULL REFERENCES pharmacy_requisitions(id),
    material_id                 VARCHAR(32) NOT NULL,
    requested_quantity          NUMERIC(15,4) NOT NULL,   -- 申领数量
    approved_quantity           NUMERIC(15,4),            -- 批准数量
    dispensed_quantity          NUMERIC(15,4),            -- 实发数量
    stock_operation_detail_id   VARCHAR(32),              -- 关联库存调拨出库明细ID
    metadata                    JSONB
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_dispense_patient ON pharmacy_dispenses(patient_id);
CREATE INDEX IF NOT EXISTS idx_dispense_items_dispense ON pharmacy_dispense_items(dispense_id);
CREATE INDEX IF NOT EXISTS idx_return_patient ON pharmacy_returns(patient_id);
CREATE INDEX IF NOT EXISTS idx_requisition_department ON pharmacy_requisitions(department);