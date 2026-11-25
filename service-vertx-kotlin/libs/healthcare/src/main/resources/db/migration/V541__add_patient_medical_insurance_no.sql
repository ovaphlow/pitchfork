-- =====================================================
-- Healthcare — 长者档案医保字段拆分（V541）
--
-- 背景：`patients.medical_insurance` 在 V500 中的注释是「医保号 / 保险类型」，
-- 一个字段同时承载两种含义。录入长者档案时前端只能自由文本输入，无法区分
-- 险种与号码，统计与导出也无法按险种筛选。
--
-- 本迁移只加列，不改既有列类型、不删列、不回填：
--   patients.medical_insurance_no  医保号码（社保卡号 / 医保编号），缺省空串
--   patients.medical_insurance     语义收敛为「医保类型」，既有文本原样保留
--
-- 口径：
--   1. 医保类型标准取值由前端下拉提供（城镇职工基本医疗保险 / 城乡居民基本医疗保险 /
--      长期护理保险 / 商业医疗保险 / 无医保 / 其他），服务端按自由文本存储，
--      不加 CHECK、不做白名单硬校验——既有库中可能存在「城乡居民医保」等历史文本，
--      硬校验会让这些档案编辑保存失败（沿用 V500/V525「枚举由应用层管控」范式）。
--   2. medical_insurance_no 为可空自由文本，不做唯一约束：部分机构以同一号码
--      登记多位家庭成员，唯一约束会阻断正常录入。
--   3. 离院交接快照不读取本列（快照只取 id/name/gender/birth_date/
--      emergency_contact/allergies/past_history），无需额外脱敏改动。
-- =====================================================

ALTER TABLE healthcare.patients
    ADD COLUMN IF NOT EXISTS medical_insurance_no VARCHAR DEFAULT '';

COMMENT ON COLUMN healthcare.patients.medical_insurance IS '医保类型（前端下拉标准值，历史文本原样保留）';
COMMENT ON COLUMN healthcare.patients.medical_insurance_no IS '医保号码（社保卡号 / 医保编号），可空';
