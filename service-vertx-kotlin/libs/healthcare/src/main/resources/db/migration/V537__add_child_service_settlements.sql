-- =====================================================
-- Healthcare — 儿保服务结算单与收款（054 E2，V537）
--
-- 背景（054 §3 Df / §4 E2 / §8.5）：
--   儿保即时结算**独立于 `bills`**：`bills.encounter_id` 为 `NOT NULL`（V515:22），
--   社区/门诊儿童无入住周期；本迁移只新增儿保结算单 / 明细 / 收款三张表，
--   **不修改** `bills` / `bill_items` / `payments` / `encounters` 及其养老账期链，
--   **不复刻**账期、押金、红冲、结算关账。
--
-- 口径：
--   1. 明细金额 = 单价 × 数量，`ROUND_HALF_UP` 到分；结算单 `total_amount` = 明细金额之和
--      （与 `BillingEngine.money/totalOf` 同一纪律，全程 BigDecimal，不经 Double）。
--   2. `status` = 待收款/部分收款/已结清/已作废（中文枚举，应用层白名单管控，不加 CHECK，
--      沿用 V515/V516/V535 范式）。收款登记后由服务端派生：`paid = 0` → 待收款；
--      `0 < paid < total` → 部分收款；`paid >= total` → 已结清。**已作废**为保留态
--      （054 §5.5 未冻结作废路由，本轮不可达，收款时按最严拒绝）。
--   3. 单笔收款不得使累计收款超过结算单合计（应用层 400，不写入）；
--      `method` = 现金/转账/银行卡/微信/支付宝（与 V516 `payments` 同口径，CHECK 兜底）。
--   4. `subscription_id` 可选弱关联 054 E1 的套餐订阅：结算消费时累加订阅
--      `used_amount`、收款时累加订阅 `paid_amount`（E1 的 `pay_state` 读时派生据此可达「已用完」）。
--   5. `settlement_no` 服务端生成唯一单号；`operator` 一律取认证主体，客户端不得提交。
--   6. 054 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不新增 jOOQ 引用**。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

-- 结算单头
CREATE TABLE healthcare.child_service_settlements (
    id               VARCHAR(32) PRIMARY KEY,             -- 服务端生成 26 位 ULID
    settlement_no    VARCHAR NOT NULL,                    -- 服务端生成业务单号（唯一）
    patient_id       VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    subscription_id  VARCHAR(32) REFERENCES healthcare.child_package_subscriptions(id), -- 可选：套餐核销
    total_amount     NUMERIC(12,2) NOT NULL,              -- 合计 = 明细金额之和（ROUND_HALF_UP 到分）
    paid_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,    -- 累计收款（不得超过 total_amount）
    status           VARCHAR NOT NULL DEFAULT '待收款',     -- 待收款/部分收款/已结清/已作废（应用层白名单）
    operator         VARCHAR,                             -- 操作人（认证主体）
    metadata         JSONB,                               -- 扩展
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT uq_child_service_settlements_no UNIQUE (settlement_no),
    CONSTRAINT chk_child_service_settlements_total CHECK (total_amount >= 0),
    CONSTRAINT chk_child_service_settlements_paid CHECK (paid_amount >= 0)
);

COMMENT ON TABLE healthcare.child_service_settlements IS '儿保服务结算单（054 E2）：即时结算，独立于 bills，不复刻账期/押金/红冲';
COMMENT ON COLUMN healthcare.child_service_settlements.settlement_no IS '服务端生成的唯一业务单号（客户端不得提交）';
COMMENT ON COLUMN healthcare.child_service_settlements.total_amount IS '合计（元）= 明细金额之和，明细金额 = 单价 × 数量 ROUND_HALF_UP 到分';
COMMENT ON COLUMN healthcare.child_service_settlements.paid_amount IS '累计收款（元）；单笔收款不得使累计超过合计（应用层 400）';
COMMENT ON COLUMN healthcare.child_service_settlements.status IS '状态中文枚举：待收款/部分收款/已结清/已作废（应用层白名单，不加 CHECK）';
COMMENT ON COLUMN healthcare.child_service_settlements.subscription_id IS '可选弱关联 054 E1 套餐订阅：结算消费累加订阅 used_amount，收款累加订阅 paid_amount';

CREATE INDEX IF NOT EXISTS idx_child_service_settlements_patient_status
    ON healthcare.child_service_settlements(patient_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_service_settlements_subscription
    ON healthcare.child_service_settlements(subscription_id);

-- 结算单明细（金额快照，服务端计算）
CREATE TABLE healthcare.child_service_settlement_items (
    id             VARCHAR(32) PRIMARY KEY,               -- 服务端生成 26 位 ULID
    settlement_id  VARCHAR(32) NOT NULL REFERENCES healthcare.child_service_settlements(id),
    sort_order     INTEGER NOT NULL,                      -- 明细顺序（0 基，保持请求顺序）
    item_code      VARCHAR NOT NULL,                      -- 字典快照弱关联（不建外键）
    item_name      VARCHAR NOT NULL,                      -- 名称快照
    unit_price     NUMERIC(12,2) NOT NULL,                -- 单价快照（元）
    quantity       NUMERIC(20,3) NOT NULL,                -- 数量（最多 3 位小数）
    amount         NUMERIC(12,2) NOT NULL,                -- 金额 = 单价 × 数量，ROUND_HALF_UP 到分
    CONSTRAINT chk_child_service_settlement_items_price CHECK (unit_price >= 0),
    CONSTRAINT chk_child_service_settlement_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_child_service_settlement_items_amount CHECK (amount >= 0)
);

COMMENT ON TABLE healthcare.child_service_settlement_items IS '儿保结算单明细（054 E2）：字典快照 + 服务端计算金额';
COMMENT ON COLUMN healthcare.child_service_settlement_items.amount IS '金额（元）= unit_price × quantity，ROUND_HALF_UP 到分';

CREATE INDEX IF NOT EXISTS idx_child_service_settlement_items_settlement
    ON healthcare.child_service_settlement_items(settlement_id, sort_order);

-- 收款流水（台账语义：创建后不可改/删）
CREATE TABLE healthcare.child_service_settlement_payments (
    id             VARCHAR(32) PRIMARY KEY,               -- 服务端生成 26 位 ULID
    settlement_id  VARCHAR(32) NOT NULL REFERENCES healthcare.child_service_settlements(id),
    amount         NUMERIC(12,2) NOT NULL,                -- 收款金额（元），正数由应用层保证
    method         VARCHAR NOT NULL CHECK (method IN ('现金', '转账', '银行卡', '微信', '支付宝')),
    operator       VARCHAR NOT NULL,                      -- 操作人（认证主体），客户端不得提交
    remark         VARCHAR,
    metadata       JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_child_service_settlement_payments_amount CHECK (amount > 0)
);

COMMENT ON TABLE healthcare.child_service_settlement_payments IS '儿保结算收款流水（054 E2）：台账语义，创建后不可修改/删除';
COMMENT ON COLUMN healthcare.child_service_settlement_payments.amount IS '收款金额（元），正数；单笔不得使累计收款超过结算单合计';
COMMENT ON COLUMN healthcare.child_service_settlement_payments.method IS '收款方式中文枚举：现金/转账/银行卡/微信/支付宝（CHECK 兜底，与 V516 同口径）';

CREATE INDEX IF NOT EXISTS idx_child_service_settlement_payments_settlement
    ON healthcare.child_service_settlement_payments(settlement_id, created_at DESC);
