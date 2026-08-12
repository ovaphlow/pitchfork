-- =====================================================
-- Healthcare — 缴费记录（Payments）
-- 收费闭环的收款环节：多次部分缴费累加，余额递减；
-- 单笔缴费不得使累计缴费超过账单合计（应用层校验 400，不写入）；
-- 余额归零后账单状态流转 待缴费 → 已结清（缴费服务事务内更新）。
-- 与账单表（V515）为契约依赖：bill_id 引用 bills(id)。
-- 设计原则：
--   1. 缴费为台账语义：记录创建后不可修改/删除；
--      operator 一律取认证主体，客户端不得提交
--   2. amount NUMERIC(12,2) 正数且至多两位小数（应用层保证）；
--      余额 = 账单合计 − 累计缴费，余额不为负
--   3. 缴费方式中文枚举（现金/转账/银行卡/微信/支付宝），
--      CHECK 约束兜底（参照 V514），应用层白名单校验返回 400
--   4. 流水按账单倒序分页读取（应用层分页 + 累计聚合）
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

CREATE TABLE payments (
    id          VARCHAR(32) PRIMARY KEY,           -- ULID
    bill_id     VARCHAR(32) NOT NULL REFERENCES bills(id),
    amount      NUMERIC(12,2) NOT NULL,            -- 缴费金额（元），正数由应用层保证
    method      VARCHAR NOT NULL CHECK (method IN ('现金', '转账', '银行卡', '微信', '支付宝')),
    operator    VARCHAR NOT NULL,                  -- 操作人（认证主体），客户端不得提交
    remark      VARCHAR,                           -- 备注
    metadata    JSONB,                             -- 扩展元数据
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE payments IS '缴费记录：收费闭环收款环节，多次部分缴费累加，余额递减';
COMMENT ON COLUMN payments.bill_id IS '挂账单（V515），累计缴费不得超过账单合计';
COMMENT ON COLUMN payments.amount IS '缴费金额（元），NUMERIC(12,2) 正数且至多两位小数';
COMMENT ON COLUMN payments.method IS '缴费方式中文枚举：现金/转账/银行卡/微信/支付宝（CHECK 兜底）';
COMMENT ON COLUMN payments.operator IS '操作人，来自认证主体，客户端不得提交';
COMMENT ON COLUMN payments.remark IS '备注';
COMMENT ON COLUMN payments.metadata IS '扩展元数据';

-- 缴费流水按账单读取（应用层分页 + 累计聚合）
CREATE INDEX IF NOT EXISTS idx_payments_bill
    ON payments(bill_id, created_at DESC, id DESC);
