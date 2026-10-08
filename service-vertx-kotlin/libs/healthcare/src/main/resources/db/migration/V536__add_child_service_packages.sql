-- =====================================================
-- Healthcare — 儿保服务套餐定义与订阅（054 E1，V536）
--
-- 背景（054 §3 Df / §4 E1 / §8.5）：
--   `bills.encounter_id` 为 `NOT NULL`（V515:22），社区/门诊儿童无入住周期；
--   儿保套餐购买与付费状态**独立于 `bills`**，不复刻账期/押金/红冲/结算关账。
--   本迁移只新增儿保套餐定义与订阅两张表，**不修改** `bills` / `bill_items` /
--   `payments` / `settlements` 及其养老账期链，也不引用 `encounters`。
--
-- 口径：
--   1. `status` = 启用/停用；`pay_state` = 未付/部分/已付/已用完（中文枚举，应用层白名单
--      管控，不加 CHECK，沿用 V515/V526/V535 范式）。`pay_state` **不落库**，由服务端按
--      `amount` / `paid_amount` / `used_amount` 读时派生（054 §5.8）。
--   2. `amount` 为购买时的套餐价快照（服务端从 `child_service_packages.price` 复制，
--      客户端不得提交）；`paid_amount` 由收款流程累加（054 E2）；`used_amount` 由核销流程
--      累加（054 E2 结算消费套餐时），本表只保证初值 0。
--   3. `package_code` / `package_name` 为套餐快照，套餐改价/停用不影响已购订阅。
--   4. `items` 为服务项快照数组 `[{item_code,item_name,quantity}]`，`item_code` 为字典快照
--      弱关联（不建外键，与 `bills`/`bill_items` 的字典快照口径一致）。
--   5. `operator` / `created_by` 一律取认证主体，客户端不得提交。
--   6. 054 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不新增 jOOQ 引用**。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

-- 儿保服务套餐定义（主数据）
CREATE TABLE healthcare.child_service_packages (
    id           VARCHAR(32) PRIMARY KEY,                -- 服务端生成 26 位 ULID
    code         VARCHAR NOT NULL,                       -- 套餐编码（业务唯一键）
    name         VARCHAR NOT NULL,                       -- 套餐名称
    price        NUMERIC(12,2) NOT NULL,                 -- 套餐价（元，精确到分）
    items        JSONB NOT NULL DEFAULT '[]'::jsonb,     -- 服务项快照 [{item_code,item_name,quantity}]
    status       VARCHAR NOT NULL DEFAULT '启用',         -- 启用/停用（应用层白名单）
    metadata     JSONB,                                  -- 扩展
    created_by   VARCHAR,                                -- 创建人（认证主体）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    CONSTRAINT uq_child_service_packages_code UNIQUE (code),
    CONSTRAINT chk_child_service_packages_price CHECK (price >= 0),
    CONSTRAINT chk_child_service_packages_items CHECK (jsonb_typeof(items) = 'array')
);

COMMENT ON TABLE healthcare.child_service_packages IS '儿保服务套餐定义（054 E1）：独立于 bills 的即时结算套餐主数据';
COMMENT ON COLUMN healthcare.child_service_packages.code IS '套餐编码，业务唯一键';
COMMENT ON COLUMN healthcare.child_service_packages.price IS '套餐价（元），NUMERIC(12,2) 精确到分，服务端按 ROUND_HALF_UP 校验入参精度';
COMMENT ON COLUMN healthcare.child_service_packages.items IS '服务项快照 JSONB 数组 [{item_code,item_name,quantity}]，item_code 为弱关联字典快照';
COMMENT ON COLUMN healthcare.child_service_packages.status IS '状态中文枚举：启用/停用（应用层白名单，不加 CHECK）';

CREATE INDEX IF NOT EXISTS idx_child_service_packages_status
    ON healthcare.child_service_packages(status, created_at DESC);

-- 套餐订阅（购买记录，患者维度）
CREATE TABLE healthcare.child_package_subscriptions (
    id            VARCHAR(32) PRIMARY KEY,               -- 服务端生成 26 位 ULID
    patient_id    VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    package_id    VARCHAR(32) NOT NULL REFERENCES healthcare.child_service_packages(id),
    package_code  VARCHAR NOT NULL,                      -- 购买时套餐编码快照
    package_name  VARCHAR NOT NULL,                      -- 购买时套餐名称快照
    purchased_at  TIMESTAMPTZ NOT NULL DEFAULT now(),    -- 购买时刻
    amount        NUMERIC(12,2) NOT NULL,                -- 应付金额 = 购买时套餐价快照（服务端派生）
    paid_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,      -- 已收金额（054 E2 收款累加）
    used_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,      -- 已核销金额（054 E2 结算消费累加）
    status        VARCHAR NOT NULL DEFAULT '启用',         -- 启用/停用（应用层白名单）
    metadata      JSONB,                                 -- 扩展
    operator      VARCHAR,                               -- 操作人（认证主体）
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT chk_child_package_subscriptions_amount CHECK (amount >= 0),
    CONSTRAINT chk_child_package_subscriptions_paid CHECK (paid_amount >= 0),
    CONSTRAINT chk_child_package_subscriptions_used CHECK (used_amount >= 0)
);

COMMENT ON TABLE healthcare.child_package_subscriptions IS '儿保套餐订阅（054 E1）：患者维度购买记录，付费状态读时派生';
COMMENT ON COLUMN healthcare.child_package_subscriptions.amount IS '应付金额（元）= 购买时 child_service_packages.price 快照，客户端不得提交';
COMMENT ON COLUMN healthcare.child_package_subscriptions.paid_amount IS '已收金额（元），由 054 E2 收款流程累加，初始 0';
COMMENT ON COLUMN healthcare.child_package_subscriptions.used_amount IS '已核销金额（元），由 054 E2 结算消费累加，初始 0';
COMMENT ON COLUMN healthcare.child_package_subscriptions.status IS '状态中文枚举：启用/停用（应用层白名单，不加 CHECK）';
COMMENT ON COLUMN healthcare.child_package_subscriptions.package_code IS '套餐编码快照，套餐改价/停用不影响已购订阅';

CREATE INDEX IF NOT EXISTS idx_child_package_subscriptions_patient_status
    ON healthcare.child_package_subscriptions(patient_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_package_subscriptions_package
    ON healthcare.child_package_subscriptions(package_id);
