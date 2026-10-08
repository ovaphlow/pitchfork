-- =====================================================
-- Healthcare — 儿保服务发票台账（054 E3，V538）
--
-- 背景（054 §3 Df / §4 E3 / §8.5 / §11.2 Q9）：
--   儿保发票**独立于 `bills`**：只做**发票台账 + 票号 + 状态（已开具/已作废）**，
--   前端本地打印；**不做税控对接 / 电子发票开票**。
--   本迁移只新增发票台账一张表，**不修改** `bills` / `bill_items` / `payments` /
--   `encounters` 及其养老账期链。
--
-- 口径：
--   1. `invoice_no` 业务唯一（服务端生成或客户端提交纸质票号）；重复 → 409。
--   2. `status` = 已开具/已作废（中文枚举，应用层白名单管控，不加 CHECK）；
--      **单向**：`已开具 → 已作废`，`已作废` 为终态；非法流转 400。
--   3. `amount` NUMERIC(12,2)：关联结算单时取 `child_service_settlements.total_amount` 快照、
--      关联套餐订阅时取 `child_package_subscriptions.amount` 快照，均**服务端派生**；
--      无关联时由客户端提交（正数、至多两位小数）。
--   4. `settlement_id` / `subscription_id` 至多关联其一（DB CHECK 兜底 + 应用层校验）。
--   5. `issued_at` 服务端开具时刻；`operator` 一律取认证主体，客户端不得提交。
--   6. 054 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不新增 jOOQ 引用**。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

CREATE TABLE healthcare.child_service_invoices (
    id               VARCHAR(32) PRIMARY KEY,             -- 服务端生成 26 位 ULID
    invoice_no       VARCHAR NOT NULL,                    -- 发票号（业务唯一键）
    patient_id       VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    title            VARCHAR NOT NULL,                    -- 发票抬头/项目名称
    amount           NUMERIC(12,2) NOT NULL,              -- 金额（元），关联时取被关联单据快照
    status           VARCHAR NOT NULL DEFAULT '已开具',     -- 已开具/已作废（应用层白名单）
    settlement_id    VARCHAR(32) REFERENCES healthcare.child_service_settlements(id),  -- 可关联结算单
    subscription_id  VARCHAR(32) REFERENCES healthcare.child_package_subscriptions(id), -- 可关联套餐订阅
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 服务端开具时刻
    operator         VARCHAR,                             -- 操作人（认证主体）
    metadata         JSONB,                               -- 扩展
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT uq_child_service_invoices_no UNIQUE (invoice_no),
    CONSTRAINT chk_child_service_invoices_amount CHECK (amount >= 0),
    CONSTRAINT chk_child_service_invoices_link CHECK (settlement_id IS NULL OR subscription_id IS NULL)
);

COMMENT ON TABLE healthcare.child_service_invoices IS '儿保服务发票台账（054 E3）：票号唯一 + 状态单向（已开具→已作废），不做税控对接，前端本地打印';
COMMENT ON COLUMN healthcare.child_service_invoices.invoice_no IS '发票号业务唯一键：服务端生成或客户端提交纸质票号；重复 → 409';
COMMENT ON COLUMN healthcare.child_service_invoices.amount IS '金额（元）：关联结算单/订阅时取被关联单据金额快照（服务端派生），无关联时客户端提交';
COMMENT ON COLUMN healthcare.child_service_invoices.status IS '状态中文枚举：已开具/已作废（应用层白名单，单向流转，不加 CHECK）';
COMMENT ON COLUMN healthcare.child_service_invoices.settlement_id IS '可关联 054 E2 结算单；与 subscription_id 至多其一';
COMMENT ON COLUMN healthcare.child_service_invoices.subscription_id IS '可关联 054 E1 套餐订阅；与 settlement_id 至多其一';

CREATE INDEX IF NOT EXISTS idx_child_service_invoices_patient_status
    ON healthcare.child_service_invoices(patient_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_service_invoices_settlement
    ON healthcare.child_service_invoices(settlement_id);
CREATE INDEX IF NOT EXISTS idx_child_service_invoices_subscription
    ON healthcare.child_service_invoices(subscription_id);
