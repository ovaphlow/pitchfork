-- =====================================================
-- Healthcare — 离院/去世结算收束（Settlement）
-- bills.settled_at：账单结算时间（收束时写入，非空 = 已结算）；
-- encounters.settled_at：结算冻结标记（非空 = 该 encounter 全部账单已冻结，
-- 新增账单/手工加项/缴费一律 409）。不触碰 V515/V516 既有列。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

ALTER TABLE bills ADD COLUMN settled_at TIMESTAMPTZ;
COMMENT ON COLUMN bills.settled_at IS '结算时间：离院/去世结算收束时写入，非空 = 该账单已结算';

ALTER TABLE encounters ADD COLUMN settled_at TIMESTAMPTZ;
COMMENT ON COLUMN encounters.settled_at IS '结算冻结标记：非空 = 该 encounter 全部账单已冻结，新增账单/手工加项/缴费一律 409';
