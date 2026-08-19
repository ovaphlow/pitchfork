-- 000002_courses.sql
-- Training courses of the theory-training module. A course carries a topic
-- (专题), a delivery type, a lifecycle status, an optional JSONB metadata
-- extension and the creator id. The prototype has no auth context, so
-- created_by comes from the request body and defaults to empty.

CREATE TABLE IF NOT EXISTS courses (
    id         TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    topic      TEXT NOT NULL CHECK (topic IN ('客流评估与引导', '基础设施保障', '自然灾害防范', '安全应急处置', '舆情应对')),
    type       TEXT NOT NULL CHECK (type IN ('线上授课', '线下授课')),
    status     TEXT NOT NULL DEFAULT '启用' CHECK (status IN ('启用', '停用')),
    metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
