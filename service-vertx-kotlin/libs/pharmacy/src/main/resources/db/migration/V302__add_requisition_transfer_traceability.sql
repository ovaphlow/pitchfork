-- 013 护理站申领 → 药房审核预留 → 确认调拨 → 双仓库存转移双向可追溯
-- 历史 V300 申领记录没有真实库存调拨事实，新增列全部保持 NULL，不回填、不猜测。
-- 新列只由 013 服务写入；旧的 requester/metadata/stock_operation_detail_id 保留用于兼容读取。

ALTER TABLE pharmacy.pharmacy_requisitions
    ADD COLUMN destination_warehouse          VARCHAR,   -- 护理站目标库存仓库；013 新单必填且不同于源仓库 warehouse
    ADD COLUMN requester_id                   VARCHAR,   -- 创建人身份（认证 principal），服务端写入
    ADD COLUMN approved_by                    VARCHAR,   -- 审批操作人（认证 principal）
    ADD COLUMN approved_at                    TIMESTAMPTZ,
    ADD COLUMN dispensed_by                   VARCHAR,   -- 确认调拨操作人（认证 principal）；dispensed_at 保留为确认时间
    ADD COLUMN cancelled_by                   VARCHAR,   -- 取消操作人（认证 principal）
    ADD COLUMN cancelled_at                   TIMESTAMPTZ,
    ADD COLUMN cancel_reason                  VARCHAR,   -- 取消原因必填，不写自由 JSONB
    ADD COLUMN idempotency_key                VARCHAR,   -- 创建请求幂等身份；同键不同内容返回 409
    ADD COLUMN request_fingerprint            VARCHAR,   -- 创建请求规范化内容摘要
    ADD COLUMN updated_at                     TIMESTAMPTZ; -- 每次合法状态转换由服务端更新

-- 幂等键局部唯一索引：NULL 历史记录不受约束
CREATE UNIQUE INDEX idx_requisition_idempotency_key
    ON pharmacy.pharmacy_requisitions(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 列表按状态 + 创建时间倒序查询索引
CREATE INDEX idx_requisition_status_created
    ON pharmacy.pharmacy_requisitions(status, created_at DESC);

-- 目标仓库查询索引
CREATE INDEX idx_requisition_destination_warehouse
    ON pharmacy.pharmacy_requisitions(destination_warehouse);

ALTER TABLE pharmacy.pharmacy_requisition_items
    ADD COLUMN unit                               VARCHAR,   -- 013 新记录固定 PACKAGE；历史 NULL，不猜测拆零语义
    ADD COLUMN lot_id                             VARCHAR,   -- 已审批并实际调拨的单一批次；批次物资必填、非批次物资为空
    ADD COLUMN outbound_stock_operation_detail_id VARCHAR,   -- 药房源仓库 OUTBOUND 操作明细 ID
    ADD COLUMN inbound_stock_operation_detail_id  VARCHAR;   -- 护理站目标仓库 INBOUND 操作明细 ID
