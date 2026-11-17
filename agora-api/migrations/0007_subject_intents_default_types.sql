-- 0007_subject_intents_default_types.sql — 回填存量用户类型
--
-- 0006 把机构/岗位参与者登记为 Agora 用户但类型留空。这里按业务关系补类型：
--   1. 机构成员、岗位创建人 → recruiter（招聘方）
--   2. 其余已登记账号 → job_seeker（求职者）
-- 这是对存量数据的推定，不改变登录后由 PUT /me/intent 写入的真实选择。

UPDATE subject_intents
SET user_type = 'recruiter', updated_at = NOW()
WHERE user_type IS NULL
  AND subject_id IN (
      SELECT subject_id FROM organization_members WHERE subject_id <> ''
      UNION
      SELECT created_by FROM job_positions WHERE created_by <> ''
  );

UPDATE subject_intents
SET user_type = 'job_seeker', updated_at = NOW()
WHERE user_type IS NULL;
