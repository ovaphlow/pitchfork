-- 000005_exam_papers.sql
-- Exam papers (试卷) of the theory-training module. A paper carries a
-- required title, the exam duration in minutes, the pass score on a
-- 0-100 scale and a generation_strategy JSONB object that describes how
-- many questions of each type (单选/多选/判断/填空) automatic paper
-- generation must pick from the question bank. questions is the
-- read-only snapshot produced by POST /papers/{id}/generate: it holds
-- the picked question summaries (id/type/difficulty/content/options/
-- answer) and is only ever written by generation, never by the client.
-- The prototype has no auth context, so created_by comes from the
-- request body and defaults to empty. Timestamps follow the repository
-- convention (created_at + updated_at) and are managed by the service
-- layer.

CREATE TABLE IF NOT EXISTS exam_papers (
    id                  TEXT PRIMARY KEY,
    title               TEXT NOT NULL,
    duration_minutes    INTEGER NOT NULL,
    pass_score          INTEGER NOT NULL,
    generation_strategy JSONB NOT NULL,
    questions           JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by          TEXT NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
