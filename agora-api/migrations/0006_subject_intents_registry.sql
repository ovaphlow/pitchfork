-- 0006_subject_intents_registry.sql — Agora 用户注册表
--
-- 0005 最初把 user_type 设为 NOT NULL；本迁移允许「尚未选择身份」的注册行。
-- subject_intents 从这一版开始同时是 Agora 用户注册表：行存在即代表该主体
-- 使用过 Agora，Aceso 等其它产品的账号不会出现在管理台。
--
-- 回填存量 Agora 用户：机构所有者/成员、岗位创建人，类型留空。

ALTER TABLE subject_intents ALTER COLUMN user_type DROP NOT NULL;

INSERT INTO subject_intents (subject_id)
SELECT owner_subject_id FROM organizations WHERE owner_subject_id <> ''
UNION
SELECT subject_id FROM organization_members WHERE subject_id <> ''
UNION
SELECT created_by FROM job_positions WHERE created_by <> ''
ON CONFLICT (subject_id) DO NOTHING;
