-- =====================================================
-- 025 Aceso 药品目录主数据：最小演示药品集
--
-- 药品目录即 public.materials 中 category = '药品' 的记录，不新建药品主表；
-- materials.code 已 UNIQUE，本迁移以它为冲突键保证幂等（可重复执行）。
--
-- 预置范围：仅 2 条以 `DEMO-DRUG-` 前缀标识的演示药品，供存量医嘱与现场手测
-- 直接走通「医生开药 → 医嘱绑定物资 → 药房发药」闭环；真实药品请通过
-- `库存计量 → 物资` 新建（不要复用 DEMO-DRUG-* 编码）。
--
-- id 为固定 26 位 Crockford Base32 ULID 常量：保证重复执行得到同一行、
-- 且可在测试/文档中稳定引用，不依赖 NOW()/随机数。
--
-- 【已知代价，必须在真实药品上避免】演示药品 enable_batch_control = false：
--   - 表示这两条药品没有批次/效期管控，发药时 lot_id 为空；
--   - 库存详情只按 (warehouse, material_id, lot_id) 记账，无批次即整仓合并口径；
--   - 一旦该物资已存在库存事实，就不能再把 enable_batch_control 改为 true
--     （否则历史无批次库存行与批次管控口径冲突）。
--   因此演示药品仅用于走通链路：真实药品应在创建时即以
--   enable_batch_control = true 新建。
--
-- 【不预置库存】仓库（如 `药库`）来自 Nexus settings，其 code 由环境决定，
--   迁移无法安全伪造仓库或库存事实；演示药品的入库由用户在
--   `库存计量 → 手工入库` 中完成（实施计划 025 §7.3）。
--
-- 本迁移不删除、不修改任何既有物资；重复执行不产生新行、不覆盖现场改动。
-- =====================================================
INSERT INTO materials (id, code, name, category, spec, base_unit, quantity_scale, enable_batch_control, status, created_at)
VALUES ('01J5D3M000000000000000A001', 'DEMO-DRUG-001', '降压药A', '药品', '10mg/片', '片', 0, false, 'ACTIVE', NOW()),
       ('01J5D3M000000000000000A002', 'DEMO-DRUG-002', '阿莫西林胶囊', '药品', '0.25g/粒', '粒', 0, false, 'ACTIVE', NOW())
ON CONFLICT (code) DO NOTHING;
