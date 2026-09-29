-- =====================================================
-- Healthcare — 演示费用字典 seed（030 W1）
-- 目的：迁移后收费链开箱可用（原状是 fee_items 恒 0 条，
--   即使写操作恢复也无法自动计费）。
-- 口径（计划 §4.1，价格为演示值，机构须按实际合同价在「费用项目」页调整）：
--   床位费 60.00 元/天 ×1
--   伙食费 15.00 元/餐次 ×1
--   护理费 低风险 30 / 中风险 50 / 高风险 80 / 无需干预 10（四个等级各一条）
--
-- 幂等与迁移安全（硬要求）：
--   1. **只补缺，不覆盖既有启用项**：每条 seed 都带「该分类（护理费按等级）
--      当前不存在启用项」的守卫（INSERT ... SELECT ... WHERE NOT EXISTS），
--      已存在配置的库迁移后字典保持不变。
--   2. V520 已建两条部分唯一索引（uq_fee_items_nursing_level_enabled /
--      uq_fee_items_single_enabled_per_category）；仅靠 ON CONFLICT (id)
--      无法捕获「同分类/同等级启用项已存在（id 不同）」的冲突，
--      会导致迁移失败、应用起不来，因此守卫是必需的，末尾再以
--      `ON CONFLICT DO NOTHING` 兜底任意唯一冲突。
--   3. 固定 26 位 Crockford Base32 ULID：重复执行不会产生重复行。
--   4. 全部 status = '启用'；metadata 带 {"demo": true} 以区分演示数据；
--      护理费在 metadata.nursing_level 落等级（与 V520 部分唯一索引同口径）。
--   5. name 只是描述文本，不参与任何匹配（护理费按等级、床位/伙食按分类）。
-- =====================================================

-- 床位费（该分类没有启用项时才补）
INSERT INTO healthcare.fee_items
    (id, category, name, unit_price, status, remark, metadata, created_at, updated_at)
SELECT '01JFEESEED000000000000BED1', '床位费', '床位费（演示价）', 60.00, '启用',
       '演示默认价，请按实际合同价调整；按天计费', '{"demo": true}'::jsonb, now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM healthcare.fee_items WHERE category = '床位费' AND status = '启用'
)
ON CONFLICT DO NOTHING;

-- 伙食费（该分类没有启用项时才补）
INSERT INTO healthcare.fee_items
    (id, category, name, unit_price, status, remark, metadata, created_at, updated_at)
SELECT '01JFEESEED000000000000MEA1', '伙食费', '伙食费（演示价）', 15.00, '启用',
       '演示默认价，请按实际合同价调整；按账期内折合餐次计费', '{"demo": true}'::jsonb, now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM healthcare.fee_items WHERE category = '伙食费' AND status = '启用'
)
ON CONFLICT DO NOTHING;

-- 护理费·低风险（该等级没有启用项时才补）
INSERT INTO healthcare.fee_items
    (id, category, name, unit_price, status, remark, metadata, created_at, updated_at)
SELECT '01JFEESEED000000000000NRS1', '护理费', '护理费·低风险（演示价）', 30.00, '启用',
       '演示默认价，请按实际合同价调整；按护理等级匹配（低风险）',
       '{"demo": true, "nursing_level": "低风险"}'::jsonb, now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM healthcare.fee_items
    WHERE category = '护理费' AND metadata->>'nursing_level' = '低风险' AND status = '启用'
)
ON CONFLICT DO NOTHING;

-- 护理费·中风险（该等级没有启用项时才补）
INSERT INTO healthcare.fee_items
    (id, category, name, unit_price, status, remark, metadata, created_at, updated_at)
SELECT '01JFEESEED000000000000NRS2', '护理费', '护理费·中风险（演示价）', 50.00, '启用',
       '演示默认价，请按实际合同价调整；按护理等级匹配（中风险）',
       '{"demo": true, "nursing_level": "中风险"}'::jsonb, now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM healthcare.fee_items
    WHERE category = '护理费' AND metadata->>'nursing_level' = '中风险' AND status = '启用'
)
ON CONFLICT DO NOTHING;

-- 护理费·高风险（该等级没有启用项时才补）
INSERT INTO healthcare.fee_items
    (id, category, name, unit_price, status, remark, metadata, created_at, updated_at)
SELECT '01JFEESEED000000000000NRS3', '护理费', '护理费·高风险（演示价）', 80.00, '启用',
       '演示默认价，请按实际合同价调整；按护理等级匹配（高风险）',
       '{"demo": true, "nursing_level": "高风险"}'::jsonb, now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM healthcare.fee_items
    WHERE category = '护理费' AND metadata->>'nursing_level' = '高风险' AND status = '启用'
)
ON CONFLICT DO NOTHING;

-- 护理费·无需干预（该等级没有启用项时才补）
INSERT INTO healthcare.fee_items
    (id, category, name, unit_price, status, remark, metadata, created_at, updated_at)
SELECT '01JFEESEED000000000000NRS4', '护理费', '护理费·无需干预（演示价）', 10.00, '启用',
       '演示默认价，请按实际合同价调整；按护理等级匹配（无需干预）',
       '{"demo": true, "nursing_level": "无需干预"}'::jsonb, now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM healthcare.fee_items
    WHERE category = '护理费' AND metadata->>'nursing_level' = '无需干预' AND status = '启用'
)
ON CONFLICT DO NOTHING;
