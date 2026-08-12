-- =====================================================
-- Healthcare — 费用项目字典（Fee Items）
-- 养老收费的基础数据字典，为账单自动计费提供单价来源。
-- 与押金子任务（V513）无依赖；账单明细为快照，
-- 字典改价/停用不影响已生成账单。
-- 设计原则：
--   1. 分类中文枚举（床位费/护理费/伙食费/个性化服务费/押金/其他），
--      CHECK 约束兜底，应用层白名单校验返回 400
--   2. 单价 NUMERIC(12,2) 正数，应用层保证 > 0 且至多两位小数
--   3. 状态 启用/停用（中文值，应用层白名单管控，默认 启用），
--      状态流转通过 PATCH 独立进行，不改动字典其他字段
--   4. 名称唯一性与分页均遵循共享约定，不设额外约束
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

CREATE TABLE fee_items (
    id          VARCHAR(32) PRIMARY KEY,           -- ULID
    category    VARCHAR NOT NULL CHECK (category IN ('床位费', '护理费', '伙食费', '个性化服务费', '押金', '其他')),
    name        VARCHAR NOT NULL,                  -- 费用项目名称
    unit_price  NUMERIC(12,2) NOT NULL,            -- 单价（元），正数由应用层保证
    status      VARCHAR NOT NULL DEFAULT '启用',   -- 状态：启用/停用（应用层白名单管控）
    remark      VARCHAR,                           -- 备注
    metadata    JSONB,                             -- 扩展元数据
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE fee_items IS '费用项目字典：养老收费基础数据（为账单自动计费提供单价来源）';
COMMENT ON COLUMN fee_items.category IS '分类中文枚举：床位费/护理费/伙食费/个性化服务费/押金/其他';
COMMENT ON COLUMN fee_items.name IS '费用项目名称';
COMMENT ON COLUMN fee_items.unit_price IS '单价（元），NUMERIC(12,2) 正数';
COMMENT ON COLUMN fee_items.status IS '状态中文枚举：启用/停用（应用层白名单管控，默认启用）';
COMMENT ON COLUMN fee_items.remark IS '备注';
COMMENT ON COLUMN fee_items.metadata IS '扩展元数据';

-- 字典列表按状态/分类过滤查询
CREATE INDEX IF NOT EXISTS idx_fee_items_status ON fee_items(status);
CREATE INDEX IF NOT EXISTS idx_fee_items_category ON fee_items(category);
