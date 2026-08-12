-- =====================================================
-- Healthcare — 账单与账单明细（Bills）
-- 按月账单自动计费 + 手工加项；明细为字典快照（item 编码/名称/单价），
-- 来源 自动/手工；账单状态初始 待缴费。
-- 与费用项目字典（V514）为契约依赖：计费单价取字典启用项，
-- 明细落库后为快照，字典改价/停用不影响已生成账单。
-- 设计原则：
--   1. 账单头：encounter + 账期（自然月起止 DATE，首/尾月按实际在院日裁剪），
--      (encounter_id, period_start, period_end) 唯一约束 → 同账期重复生成 409
--   2. 状态 待缴费/已结清/已结算（中文枚举，应用层白名单管控，默认 待缴费；
--      流转由缴费/结算子任务处理，本表只保证初始值）
--   3. 明细行：来源 自动/手工，item 编码（字典项 ULID）/名称/单价快照，
--      数量（床位/护理=天数，伙食=折合餐次，手工=手填数量），金额=单价×数量
--   4. 金额 NUMERIC(12,2)：单价×数量 ROUND_HALF_UP 到分；合计=明细之和
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

CREATE TABLE bills (
    id              VARCHAR(32) PRIMARY KEY,           -- ULID
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    period_start    DATE NOT NULL,                     -- 账期起（自然月裁剪后）
    period_end      DATE NOT NULL,                     -- 账期止（自然月裁剪后）
    status          VARCHAR NOT NULL DEFAULT '待缴费', -- 待缴费/已结清/已结算（应用层白名单管控）
    total_amount    NUMERIC(12,2) NOT NULL,            -- 金额合计 = 明细之和
    metadata        JSONB,                             -- 扩展元数据
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_bills_encounter_period UNIQUE (encounter_id, period_start, period_end)
);
COMMENT ON TABLE bills IS '账单：按月自动计费 + 手工加项，同 encounter 同账期唯一';
COMMENT ON COLUMN bills.encounter_id IS '挂 encounter（入住周期）';
COMMENT ON COLUMN bills.period_start IS '账期起（自然月，首/尾月按实际在院日裁剪）';
COMMENT ON COLUMN bills.period_end IS '账期止（自然月，首/尾月按实际在院日裁剪）';
COMMENT ON COLUMN bills.status IS '状态中文枚举：待缴费/已结清/已结算（初始 待缴费）';
COMMENT ON COLUMN bills.total_amount IS '金额合计（元）= 明细之和，明细金额 ROUND_HALF_UP 到分';

-- 账单明细：字典快照，来源 自动/手工
CREATE TABLE bill_items (
    id          VARCHAR(32) PRIMARY KEY,           -- ULID
    bill_id     VARCHAR(32) NOT NULL REFERENCES bills(id) ON DELETE CASCADE,
    source      VARCHAR NOT NULL,                  -- 来源：自动/手工（应用层白名单管控）
    item_code   VARCHAR NOT NULL,                  -- 字典项编码快照（费用项目 ULID）
    item_name   VARCHAR NOT NULL,                  -- 费用项目名称快照
    unit_price  NUMERIC(12,2) NOT NULL,            -- 单价快照（元）；手工加项可覆盖字典单价
    quantity    NUMERIC(12,2) NOT NULL,            -- 数量：床位/护理=天数、伙食=折合餐次、手工=手填
    amount      NUMERIC(12,2) NOT NULL,            -- 金额（元）= 单价×数量，ROUND_HALF_UP 到分
    remark      VARCHAR,                           -- 备注（手工加项说明等）
    metadata    JSONB,                             -- 扩展元数据
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE bill_items IS '账单明细：费用项目快照（编码/名称/单价），字典改价/停用不影响已生成账单';
COMMENT ON COLUMN bill_items.source IS '来源中文枚举：自动/手工';
COMMENT ON COLUMN bill_items.item_code IS '费用项目编码快照（字典项 ULID）';
COMMENT ON COLUMN bill_items.item_name IS '费用项目名称快照';
COMMENT ON COLUMN bill_items.unit_price IS '单价快照（元），手工加项可覆盖字典单价';
COMMENT ON COLUMN bill_items.quantity IS '数量：床位/护理=计费天数、伙食=折合餐次（正常1/部分0.5/未就餐拒食0）、手工=手填数量';
COMMENT ON COLUMN bill_items.amount IS '金额（元）= 单价×数量，ROUND_HALF_UP 到分';

-- 账期倒序查询账单（应用层分页）
CREATE INDEX IF NOT EXISTS idx_bills_encounter ON bills(encounter_id, period_start DESC, id DESC);
-- 账单明细按创建顺序读取（合计与展示）
CREATE INDEX IF NOT EXISTS idx_bill_items_bill ON bill_items(bill_id, created_at, id);
