-- =====================================================
-- Healthcare — 押金核销（Deposit Offset）
-- 养老收费链路修复的押金核销子任务：结算收束时由操作员手工核销押金
-- 抵扣欠费，核销与冻结在同一事务内完成。
-- 核销复用既有两张台账，不新建表、不给 payments/deposit_records 增删列：
--   payments        一行（method = 押金，账单余额因此递减）
--   deposit_records 一行（type = 核销，押金余额因此递减）
-- 本迁移只放开 payments.method 的 CHECK，使「押金」成为合法取值。
-- 设计原则：
--   1. 只做 DROP + 同名重建，既有 5 个方式（现金/转账/银行卡/微信/支付宝）不变；
--      表内既有数据全部满足新约束，NOT VALID 不适用
--   2. 「押金」只由结算收束的押金核销写入，客户端不可通过缴费接口提交
--      （PaymentService.methods 白名单保持 5 值，DB CHECK 放宽只为服务端写入服务）
--   3. 核销是押金余额与账单余额同减的内部结转，不产生现金流入，不改变账单合计
--   4. 核销产生的 payments 行计入「已缴」，恒等式 应缴 − 已缴 = 欠费 不变
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_method_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_method_check
    CHECK (method IN ('现金', '转账', '银行卡', '微信', '支付宝', '押金'));

COMMENT ON COLUMN payments.method IS '缴费方式中文枚举：现金/转账/银行卡/微信/支付宝/押金（CHECK 兜底）；其中「押金」只由结算收束的押金核销写入，客户端不可通过缴费接口提交';
