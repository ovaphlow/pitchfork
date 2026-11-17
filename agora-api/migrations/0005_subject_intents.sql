-- 0005_subject_intents.sql — Agora 用户类型
--
-- subject_id 是 platformd identity_subjects.id 的跨库引用（不建外键）。
-- 同一账号可以切换求职/招聘身份；本表只保存最近一次选择，不参与权限判定。
-- 现有账号没有选择时保持缺失，管理台显示「未选择」，不做默认回填。

CREATE TABLE IF NOT EXISTS subject_intents (
    subject_id TEXT PRIMARY KEY,
    user_type  TEXT NOT NULL CHECK (user_type IN ('job_seeker', 'recruiter')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
