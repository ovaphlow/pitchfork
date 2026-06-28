-- 用户表添加部门编码字段
ALTER TABLE users ADD COLUMN IF NOT EXISTS department_code VARCHAR;
