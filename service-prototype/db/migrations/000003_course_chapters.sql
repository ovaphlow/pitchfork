-- 000003_course_chapters.sql
-- Course chapters (课程章节) of the theory-training module. A chapter
-- belongs to exactly one course; the foreign key cascades so deleting a
-- course removes its chapters. Each chapter carries a sort order, a
-- required title, a JSONB content-block array (validated by the API to
-- contain only 视频/图文/互动问答 blocks) and an optional quiz_config
-- JSONB extension that carries the interactive quiz (题目/选项/答案/即时
-- 反馈). Timestamps follow the repository convention (created_at +
-- updated_at) and are managed by the service layer.

CREATE TABLE IF NOT EXISTS course_chapters (
    id          TEXT PRIMARY KEY,
    course_id   TEXT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    title       TEXT NOT NULL,
    blocks      JSONB NOT NULL DEFAULT '[]'::jsonb,
    quiz_config JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
