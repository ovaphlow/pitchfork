-- =====================================================
-- Healthcare — 账单红冲（Bill Reversal，034 A2 反向单）
-- 034 决策 A2：账单生成后错了不修改、不删除既有凭证，而是新建一张
-- 金额取反的红字账单抵消它，并双向关联；「谁在何时因为什么红冲了哪张单」
-- 四个问题全部可答。
-- 本迁移只给既有 bills 表加四列 + 重排唯一约束，不新增表、不改状态枚举、
-- 不回填历史数据、不做自动重算：
--   reversal_of     红字单指向被冲的原单（原单侧为空）
--   reversal_reason 红冲原因（写在原单上）
--   reversed_by     红冲操作人（认证主体，写在原单上，客户端不得提交）
--   reversed_at     红冲时刻（写在原单上）
-- 设计原则：
--   1. 原单不动：total_amount/status/bill_items 全部保留，红冲只在原单上留痕；
--      红字单 status = 待缴费、total_amount = 原单合计取反（严格 < 0）、
--      period_start/period_end 复制原单 → 全仓金额聚合按正负相抵自然抵消
--      （应缴 Σ账单合计 = 原单 + 红字单 = 0），无需给聚合加过滤。
--   2. 唯一性重排（关键，否则红字单与原单同账期必然冲突）：
--      uq_bills_encounter_period 由表约束降级为**部分唯一索引**，只约束
--      「未被红冲的原单」（reversal_of IS NULL AND reversed_at IS NULL）——
--      语义：每 encounter 每账期至多一张「有效原单」；被红冲的原单让位，
--      允许用户对同账期重新生成；红字单不参与该约束。
--      ⚠ 应用层 `BillService.requireNoDuplicate` 与 precheck 的存在性判定
--      必须使用同一谓词（`effectiveBillExists`），否则出现「预检说已存在、
--      生成却能过」或反之。
--   3. uq_bills_reversal_of（部分唯一）保证每张原单至多被红冲一次。
--   4. 三条不变量约束把红冲形态钉在 DB 层：
--      红字单必须为负；红冲三列同生同灭；红字单自身不能被标记为已红冲。
--   5. 不做 A1（不新增 已作废 状态，BillingEngine 状态常量保持三值）、
--      不触碰 V515/V516/V518/V519 既有列与语义、不给历史账单回填任何红冲信息。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

ALTER TABLE healthcare.bills
    ADD COLUMN reversal_of     VARCHAR(32) REFERENCES bills(id),
    ADD COLUMN reversal_reason VARCHAR,
    ADD COLUMN reversed_by     VARCHAR,
    ADD COLUMN reversed_at     TIMESTAMPTZ;

COMMENT ON COLUMN bills.reversal_of IS '红字单指向被冲的原单（bills.id）；非空 = 本单是红字（反向）单，其 total_amount 必须为负。原单侧保持为空';
COMMENT ON COLUMN bills.reversal_reason IS '红冲原因（写在原单上）；与 reversed_by/reversed_at 同生同灭，客户端不得提交，取服务端校验后的请求体 reason';
COMMENT ON COLUMN bills.reversed_by IS '红冲操作人（写在原单上），来自认证主体，客户端不得提交';
COMMENT ON COLUMN bills.reversed_at IS '红冲时刻（写在原单上）；非空 = 本单已被红冲，让出「有效原单」唯一性并可重新生成同账期账单';

-- 唯一性重排：表约束 → 部分唯一索引（同 encounter 同账期至多一张「有效原单」）
ALTER TABLE healthcare.bills DROP CONSTRAINT IF EXISTS uq_bills_encounter_period;
CREATE UNIQUE INDEX uq_bills_encounter_period
    ON healthcare.bills (encounter_id, period_start, period_end)
    WHERE reversal_of IS NULL AND reversed_at IS NULL;
COMMENT ON INDEX uq_bills_encounter_period IS '每 encounter 每账期至多一张「有效原单」（未被红冲且非红字单）；被红冲的原单让位以允许同账期重新生成，红字单不参与该约束';

-- 每张原单至多被红冲一次
CREATE UNIQUE INDEX uq_bills_reversal_of
    ON healthcare.bills (reversal_of)
    WHERE reversal_of IS NOT NULL;
COMMENT ON INDEX uq_bills_reversal_of IS '每张原单至多被红冲一次（reversal_of 非空侧唯一）';

-- 不变量约束：红字单必须为负 / 红冲三列同生同灭 / 红字单自身不得被标记为已红冲
ALTER TABLE healthcare.bills
    ADD CONSTRAINT ck_bills_reversal_of_negative
        CHECK (reversal_of IS NULL OR total_amount < 0),
    ADD CONSTRAINT ck_bills_reversal_audit_columns
        CHECK (
            (reversed_at IS NULL) = (reversed_by IS NULL)
            AND (reversed_at IS NULL) = (reversal_reason IS NULL)
        ),
    ADD CONSTRAINT ck_bills_reversal_not_reversed
        CHECK (reversed_at IS NULL OR reversal_of IS NULL);
