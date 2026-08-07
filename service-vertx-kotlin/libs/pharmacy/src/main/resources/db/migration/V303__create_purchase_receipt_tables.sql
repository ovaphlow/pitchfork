-- =====================================================
-- 014 药房采购与供应商收货入库闭环
-- 采购订单 → 审核 → 分批收货 → PHARMACY_PURCHASE_RECEIPT 库存 INBOUND → 订单进度
-- 供应商是订单/批次中的不可变文本快照 supplier_name，不建"供应商"表、不加主数据外键。
-- 只使用 Pharmacy V300+ 号段，不触碰 Inventory V200/V201、Healthcare V500+ 或历史 V300--V302。
-- =====================================================
SET search_path TO pharmacy, public;

-- 采购订单主表
CREATE TABLE pharmacy_purchase_orders (
    id                   VARCHAR(32) PRIMARY KEY,       -- 服务端生成 26 位 ULID
    purchase_order_no    VARCHAR NOT NULL UNIQUE,       -- 服务端生成业务单号，客户端不传入
    warehouse            VARCHAR NOT NULL,              -- 目标药房库存仓库；审核后不可变
    supplier_name        VARCHAR NOT NULL,              -- 供应商文本快照，不是主数据外键
    status               VARCHAR NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                             'DRAFT',                  -- 已创建、未审核；可编辑或取消
                             'APPROVED',               -- 已审核；字段冻结，可收货或零收货取消
                             'PARTIALLY_RECEIVED',     -- 部分收货；可继续收货或关闭余量
                             'RECEIVED',               -- 全部足额收货，终态
                             'CLOSED',                 -- 已关闭剩余收货权利，终态
                             'CANCELLED'               -- 零收货取消，终态
                         )),
    requester_id         VARCHAR,                       -- 创建人（认证 principal）
    approved_by          VARCHAR,                       -- 审核人（认证 principal）
    cancelled_by         VARCHAR,                       -- 取消人（认证 principal）
    closed_by            VARCHAR,                       -- 关闭人（认证 principal）
    approved_at          TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ,
    cancel_reason        VARCHAR,                       -- 取消原因必填，不写自由 JSONB
    close_reason         VARCHAR,                       -- 关闭原因必填，不写自由 JSONB
    idempotency_key      VARCHAR,                       -- 创建请求幂等身份；同键不同内容返回 409
    request_fingerprint  VARCHAR,                       -- 创建请求规范化内容摘要
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ                    -- 每次合法状态转换由服务端更新
);

-- 幂等键局部唯一索引：NULL 不受约束
CREATE UNIQUE INDEX idx_purchase_order_idempotency_key
    ON pharmacy_purchase_orders(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 列表按状态 + 创建时间倒序、仓库、供应商查询索引
CREATE INDEX idx_purchase_order_status_created
    ON pharmacy_purchase_orders(status, created_at DESC);
CREATE INDEX idx_purchase_order_warehouse
    ON pharmacy_purchase_orders(warehouse);
CREATE INDEX idx_purchase_order_supplier
    ON pharmacy_purchase_orders(supplier_name);

-- 采购订单明细
CREATE TABLE pharmacy_purchase_order_items (
    id                  VARCHAR(32) PRIMARY KEY,
    purchase_order_id   VARCHAR(32) NOT NULL REFERENCES pharmacy_purchase_orders(id),
    material_id         VARCHAR(32) NOT NULL,           -- 跨模块弱关联，由服务端经库存端口验证
    ordered_quantity    NUMERIC(15,4) NOT NULL CHECK (ordered_quantity > 0),
    received_quantity   NUMERIC(15,4) NOT NULL DEFAULT 0
                        CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity),
    unit                VARCHAR NOT NULL DEFAULT 'PACKAGE' CHECK (unit IN ('PACKAGE'))
);

-- 同一采购订单不能重复物资
CREATE UNIQUE INDEX idx_purchase_order_items_order_material
    ON pharmacy_purchase_order_items(purchase_order_id, material_id);

-- 收货凭证主表：一次已确认的供应商到货
CREATE TABLE pharmacy_purchase_receipts (
    id                   VARCHAR(32) PRIMARY KEY,       -- 服务端生成 26 位 ULID
    receipt_no           VARCHAR NOT NULL UNIQUE,       -- 服务端生成唯一收货单号
    purchase_order_id    VARCHAR(32) NOT NULL REFERENCES pharmacy_purchase_orders(id),
    warehouse            VARCHAR NOT NULL,              -- 从已锁定订单复制的不可变快照
    supplier_name        VARCHAR NOT NULL,              -- 从已锁定订单复制的不可变快照
    received_by          VARCHAR NOT NULL,              -- 收货人（认证 principal）
    received_at          TIMESTAMPTZ NOT NULL,          -- 服务端确认时间
    stock_operation_id   VARCHAR(32),                   -- 本次收货创建的唯一 INBOUND 库存操作单 ID（弱关联）
    idempotency_key      VARCHAR,                       -- 本次收货幂等身份
    request_fingerprint  VARCHAR,                       -- 本次收货规范化内容摘要
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 收货幂等键局部唯一索引
CREATE UNIQUE INDEX idx_purchase_receipt_idempotency_key
    ON pharmacy_purchase_receipts(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 订单与收货时间查询索引
CREATE INDEX idx_purchase_receipt_order
    ON pharmacy_purchase_receipts(purchase_order_id);
CREATE INDEX idx_purchase_receipt_received
    ON pharmacy_purchase_receipts(received_at DESC);

-- 收货明细：实际到货的物资/批次/成本事实
CREATE TABLE pharmacy_purchase_receipt_items (
    id                         VARCHAR(32) PRIMARY KEY,
    receipt_id                 VARCHAR(32) NOT NULL REFERENCES pharmacy_purchase_receipts(id),
    purchase_order_item_id     VARCHAR(32) NOT NULL REFERENCES pharmacy_purchase_order_items(id),
    material_id                VARCHAR(32) NOT NULL,    -- 服务端从订单项复制，客户端不能改写
    lot_id                     VARCHAR(32),             -- 库存端口解析或创建的批次；非批次物资为 NULL
    received_quantity          NUMERIC(15,4) NOT NULL CHECK (received_quantity > 0),
    unit                       VARCHAR NOT NULL DEFAULT 'PACKAGE' CHECK (unit IN ('PACKAGE')),
    unit_cost                  NUMERIC(15,4) NOT NULL CHECK (unit_cost >= 0),
    total_cost                 NUMERIC(18,4) NOT NULL CHECK (total_cost >= 0),
    stock_operation_detail_id  VARCHAR(32)              -- 对应 INBOUND 操作明细 ID（弱关联）
);

CREATE INDEX idx_purchase_receipt_items_receipt
    ON pharmacy_purchase_receipt_items(receipt_id);
CREATE INDEX idx_purchase_receipt_items_order_item
    ON pharmacy_purchase_receipt_items(purchase_order_item_id);
CREATE INDEX idx_purchase_receipt_items_stock_detail
    ON pharmacy_purchase_receipt_items(stock_operation_detail_id);

-- 批次组合唯一：同一订单项、同一批号在同一收货中只能出现一次；
-- 非批次物资（lot_id NULL）同一订单项只能出现一次
CREATE UNIQUE INDEX idx_purchase_receipt_items_batch
    ON pharmacy_purchase_receipt_items(receipt_id, purchase_order_item_id, lot_id)
    WHERE lot_id IS NOT NULL;
CREATE UNIQUE INDEX idx_purchase_receipt_items_no_lot
    ON pharmacy_purchase_receipt_items(receipt_id, purchase_order_item_id)
    WHERE lot_id IS NULL;
