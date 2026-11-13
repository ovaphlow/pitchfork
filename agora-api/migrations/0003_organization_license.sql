-- 0003_organization_license.sql — 机构元数据新增营业执照文件引用
--
-- 只更新 metadata 契约注释：营业执照原件存放于 Nexus files，机构记录保存
-- files.id（license_file_id），平台审核时通过 agora-api 的文件代理读取。
-- 修改契约字段必须先改 Go 侧 organizations.Metadata struct。

COMMENT ON COLUMN organizations.metadata IS
    '契约：{intro, industry, scale, website, address, credit_code, license_file_id, '
    'founded_year, tags[], contact{name,phone,email}}，键名与类型由 Go 侧 struct 校验，'
    '禁止写入未定义键；license_file_id 引用 Nexus files.id';
