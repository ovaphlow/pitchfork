-- =====================================================
-- Healthcare — 结算收束未结余额显式标记（Bill Write-Off）
-- 021 结算收束未结余额显式标记子任务：收束时把「已收妥/已被押金核销」与
-- 「未结被放弃」记录成两种不同事实，同一 status = 已结算 不再掩盖两者。
-- 本迁移只给既有 bills 表加两列，不新增表、不改状态枚举、不回填历史数据：
--   outstanding_amount 收束时刻该账单的未结余额快照（0 = 收束时已收妥或已被押金核销）
--   write_off_reason   收束时未结余额被放弃的原因（仅当 outstanding_amount > 0 时非空）
-- 设计原则：
--   1. 快照语义：outstanding_amount 只在结算收束时写入一次，等于该账单
--      「合计 − Σ缴费」（下限 0）；它是收束时刻的定格值，不是实时余额，
--      冻结后不可再改
--   2. NOT NULL DEFAULT 0：未收束账单与历史账单天然为 0，无需 NULL 分支
--   3. 关键不变量：outstanding_amount > 0 的账单必须带 write_off_reason
--      （应用层在收束时判定，无原因则 409 并整笔回滚）；
--      outstanding_amount = 0 的账单 write_off_reason 保持为空
--   4. 不对历史「已结算」账单回填或猜测未结事实：既有账单保持
--      outstanding_amount = 0。「历史收束账单的未结事实不可考」是已知残余，
--      不在本迁移里伪造
--   5. 既有汇总口径不变：应缴 − 已缴 = 欠费；减免不并入欠费，
--      单独为 Σ(已结算账单的 outstanding_amount)
-- 不触碰 V515/V516/V517/V518 既有列与约束。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

ALTER TABLE bills ADD COLUMN outstanding_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
COMMENT ON COLUMN bills.outstanding_amount IS '收束时刻该账单的未结余额快照（账单合计 − Σ缴费，下限 0）；仅在结算收束时写入一次，冻结后不可再改。0 = 收束时已收妥或已被押金核销；历史「已结算」账单不回填、不猜测，一律保持 0';

ALTER TABLE bills ADD COLUMN write_off_reason VARCHAR;
COMMENT ON COLUMN bills.write_off_reason IS '收束时未结余额被放弃的原因（减免原因）；仅当 outstanding_amount > 0 时非空，outstanding_amount = 0 时保持为空';
