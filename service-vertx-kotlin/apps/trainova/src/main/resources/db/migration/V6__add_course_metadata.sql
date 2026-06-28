-- 课程扩展属性 metadata（category, difficulty, duration, description, cover_url 等）
ALTER TABLE courses ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}';
