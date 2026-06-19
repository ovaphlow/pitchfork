# 库存模块数据结构

```sql
-- =====================================================
-- 库存管理模块 - 最终版（含拆零与包装出库）
-- 兼容 HIS 系统与养老系统
-- 表名统一使用复数，主键统一为 VARCHAR(32)
-- 物资分类采用层级路径字符串，状态字段统一管理生命周期
-- =====================================================

CREATE SCHEMA IF NOT EXISTS inventory;
SET search_path TO inventory;

-- =====================================================
-- 物资主表
-- 记录所有药品、耗材、器械、生活用品等物资的基础信息
-- 包装单位与拆零单位在此定义，库存统一以包装单位计量
-- =====================================================
CREATE TABLE materials
(
    id                   VARCHAR(32) PRIMARY KEY,         -- 主键，由应用层生成（UUID 或自定义编码）
    code                 VARCHAR NOT NULL UNIQUE,         -- 物资编码，全局唯一
    name                 VARCHAR NOT NULL,                -- 物资名称
    category             VARCHAR NOT NULL,                -- 分类路径，用 '/' 分隔层级（如 '药品/西药'、'耗材/护理耗材'）
    spec                 VARCHAR,                         -- 规格描述（如 '0.25g×24片'）
    package_unit         VARCHAR NOT NULL,                -- 包装单位，库存管理的基本单位（如 '盒'、'瓶'）
    split_unit           VARCHAR,                         -- 拆零单位，最小使用单位（如 '片'、'粒'），为 NULL 表示不支持拆零
    split_ratio          NUMERIC(10, 4),                  -- 拆零换算比：1 包装单位 = ? 拆零单位（如 24 表示 1 盒=24 片）
    enable_batch_control BOOLEAN     DEFAULT FALSE,       -- 是否启用批次/效期管控，药品通常设为 TRUE
    cost_method          VARCHAR     DEFAULT 'MOVING_AVG' -- 成本核算方法：MOVING_AVG（移动加权平均）或 FIFO（先进先出）
        CHECK (cost_method IN ('MOVING_AVG', 'FIFO')),
    metadata             JSONB,                           -- 扩展元数据，可存储医保编码、护理等级、是否处方药等任意键值对
    status               VARCHAR     DEFAULT 'ACTIVE'     -- 物资状态：ACTIVE(正常启用), INACTIVE(暂时停用), DISCONTINUED(已淘汰)
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    created_at           TIMESTAMPTZ DEFAULT now()        -- 创建时间
);

-- =====================================================
-- 批次表
-- 仅对 enable_batch_control = TRUE 的物资有效
-- 记录批号、效期、厂商等追溯信息
-- =====================================================
CREATE TABLE lots
(
    id              VARCHAR(32) PRIMARY KEY,                        -- 主键
    material_id     VARCHAR(32) NOT NULL REFERENCES materials (id), -- 所属物资ID
    batch_no        VARCHAR     NOT NULL,                           -- 批号
    production_date DATE,                                           -- 生产日期
    expiry_date     DATE,                                           -- 有效期至（到期日）
    manufacturer    VARCHAR,                                        -- 生产厂家名称
    supplier        VARCHAR,                                        -- 供应商名称
    metadata        JSONB,                                          -- 扩展元数据，如追溯码、注册证号等
    UNIQUE (material_id, batch_no)                                  -- 同一物资下批号唯一
);

-- =====================================================
-- 库存结存表
-- 记录每个仓库中每种物资（及批次）的实时库存与成本
-- 数量始终以物资的 package_unit 为单位，支持小数库存
-- =====================================================
CREATE TABLE stocks
(
    id              VARCHAR(32) PRIMARY KEY,                           -- 主键
    warehouse       VARCHAR        NOT NULL,                           -- 仓库名称或代码（如 '西药库'、'1号护理站'）
    material_id     VARCHAR(32)    NOT NULL REFERENCES materials (id), -- 物资ID
    lot_id          VARCHAR(32) REFERENCES lots (id),                  -- 批次ID，非批次管理物资为 NULL
    quantity        NUMERIC(15, 4) NOT NULL DEFAULT 0                  -- 当前库存数量（包装单位），支持小数
        CHECK (quantity >= 0),
    locked_quantity NUMERIC(15, 4) NOT NULL DEFAULT 0                  -- 已锁定数量，用于出库预占或冻结，防止超卖
        CHECK (locked_quantity >= 0),
    total_cost      NUMERIC(18, 4) NOT NULL DEFAULT 0,                 -- 结存总成本，用于移动加权平均计算实时单价
    last_updated    TIMESTAMPTZ             DEFAULT now()              -- 最后更新时间，每次库存变动时刷新
);

-- 非批次库存唯一约束：同一仓库下同一物资仅一条记录
CREATE UNIQUE INDEX idx_stocks_nonlot ON stocks (warehouse, material_id) WHERE lot_id IS NULL;
-- 批次库存唯一约束：同一仓库下同一物资同一批次唯一
CREATE UNIQUE INDEX idx_stocks_lot ON stocks (warehouse, material_id, lot_id) WHERE lot_id IS NOT NULL;

-- =====================================================
-- 统一库存操作单
-- 涵盖入库、出库、调拨三种业务类型
-- 一张单据可包含多种物资，明细行记录具体数量与成本
-- =====================================================
CREATE TABLE stock_operations
(
    id             VARCHAR(32) PRIMARY KEY,    -- 主键
    order_no       VARCHAR NOT NULL UNIQUE,    -- 业务单号，全局唯一
    operation_type VARCHAR NOT NULL            -- 操作类型：INBOUND(入库), OUTBOUND(出库), TRANSFER(调拨)
        CHECK (operation_type IN ('INBOUND', 'OUTBOUND', 'TRANSFER')),
    warehouse      VARCHAR,                    -- 目标仓库（入库/出库时使用），调拨时可为空
    from_warehouse VARCHAR,                    -- 调出仓库（仅调拨时填写）
    to_warehouse   VARCHAR,                    -- 调入仓库（仅调拨时填写）
    supplier       VARCHAR,                    -- 供应商名称（入库时填写）
    status         VARCHAR     DEFAULT 'DRAFT' -- 单据状态：DRAFT(草稿), CONFIRMED(已确认，库存已变动), CANCELLED(已作废)
        CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    metadata       JSONB,                      -- 扩展信息，可存储申请科室、关联单据号、调拨原因等
    created_at     TIMESTAMPTZ DEFAULT now(),  -- 创建时间
    confirmed_at   TIMESTAMPTZ                 -- 确认时间，即库存变动生效时间
);

-- =====================================================
-- 库存操作明细
-- 记录每张操作单涉及的物资、批次、数量、成本等
-- 支持整包装出库和拆零出库两种模式
-- =====================================================
CREATE TABLE stock_operation_details
(
    id              VARCHAR(32) PRIMARY KEY,                                                    -- 主键
    operation_id    VARCHAR(32)    NOT NULL REFERENCES stock_operations (id) ON DELETE CASCADE, -- 操作单ID，级联删除
    material_id     VARCHAR(32)    NOT NULL REFERENCES materials (id),                          -- 物资ID
    lot_id          VARCHAR(32) REFERENCES lots (id),                                           -- 批次ID，出库时可指定或由系统按 FIFO 自动分配
    quantity        NUMERIC(15, 4) NOT NULL                                                     -- 包装单位数量，始终为正数，用于库存变动计算
        CHECK (quantity > 0),
    unit            VARCHAR DEFAULT 'PACKAGE'                                                   -- 出库时使用的单位：PACKAGE(整包装), SPLIT(拆零)
        CHECK (unit IN ('PACKAGE', 'SPLIT')),
    split_quantity  NUMERIC(15, 4),                                                             -- 拆零单位下的原始数量（如 6 片），仅 unit='SPLIT' 时记录
    unit_cost       NUMERIC(15, 4),                                                             -- 单位成本（包装单位单价），入库时必填，出库时可由成本算法自动计算
    total_cost      NUMERIC(18, 4)                                                             -- 本行总成本（quantity × unit_cost）
);

-- =====================================================
-- 盘点表
-- 直接记录每次盘点的明细，包含账面数、实盘数及差异
-- 同一仓库同一天默认视为一次盘点，多次盘点由应用层控制（如只保留最后一次确认的记录）
-- =====================================================
CREATE TABLE stock_checks
(
    id              VARCHAR(32) PRIMARY KEY,                                                     -- 主键
    warehouse       VARCHAR     NOT NULL,                                                        -- 盘点仓库
    check_date      DATE        NOT NULL,                                                        -- 盘点日期
    material_id     VARCHAR(32) NOT NULL REFERENCES materials (id),                              -- 物资ID
    lot_id          VARCHAR(32) REFERENCES lots (id),                                            -- 批次ID，非批次物资为空
    book_quantity   NUMERIC(15, 4),                                                              -- 账面数量（包装单位），盘点瞬间固化
    actual_quantity NUMERIC(15, 4),                                                              -- 实盘数量（包装单位）
    diff_quantity   NUMERIC(15, 4) GENERATED ALWAYS AS (actual_quantity - book_quantity) STORED, -- 差异数量，盘盈为正，盘亏为负，自动计算
    status          VARCHAR     DEFAULT 'DRAFT'                                                  -- 状态：DRAFT(草稿), CONFIRMED(已确认，差异生效), CANCELLED(作废)
        CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    remark          TEXT,                                                                        -- 备注，可记录盘点人员、初盘/复盘说明等
    created_at      TIMESTAMPTZ DEFAULT now(),                                                   -- 创建时间
    confirmed_at    TIMESTAMPTZ                                                                  -- 确认时间，差异调整的生效时间
);

-- 按仓库与日期快速查询盘点的索引
CREATE INDEX idx_stock_checks_warehouse_date ON stock_checks (warehouse, check_date);
```
