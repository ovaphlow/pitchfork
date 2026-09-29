-- =====================================================
-- Healthcare — 费用项目字典：启用态唯一性兜底索引（030 W1）
-- 背景：护理费不再按 name 匹配评估等级，改为按服务端字段
--   metadata.nursing_level（API 顶层拉平为 nursing_level）匹配；
--   「同一护理等级至多一条启用项」由 DB 部分唯一索引兜底。
-- 说明：
--   1. 不新增列（避免重生成 jOOQ）：等级落既有 metadata JSONB。
--   2. 索引只覆盖 status = '启用' 的行，停用项不占用等级。
--   3. 床位费/伙食费沿用「同一分类至多一条启用项」的既有服务端口径，
--      同样以部分唯一索引兜底。
--   4. 非护理费行不得携带等级只能由服务端约束（JSONB 无法在 DB 层表达），
--      见计划 §7 R1。
-- 可重复执行：IF NOT EXISTS。
-- =====================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_fee_items_nursing_level_enabled
    ON healthcare.fee_items ((metadata->>'nursing_level'))
    WHERE category = '护理费' AND status = '启用';

CREATE UNIQUE INDEX IF NOT EXISTS uq_fee_items_single_enabled_per_category
    ON healthcare.fee_items (category)
    WHERE status = '启用' AND category IN ('床位费', '伙食费');
