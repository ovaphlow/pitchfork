-- 000031_opinion_reviews.sql
-- Opinion reviews (舆情复盘记录) of the public-opinion-response
-- training module (module 5): the after-action review report (舆情复盘
-- 报告) of each drill run, written after the disposition ends (the
-- 「舆情复盘」 phase, one review per run, run_id UNIQUE). The five
-- sections are the five text fields 事件经过 (case_summary) / 处置亮点
-- (highlights) / 存在问题 (problems) / 经验教训 (lessons) / 改进建议
-- (suggestions), all optional and defaulting to empty. metadata follows
-- the repository JSONB extension-field convention and defaults to an
-- empty object. The run foreign key cascades, so deleting a run removes
-- its review (the in-memory opinion store mirrors this through
-- DeleteByRun, which cleans the reviews alongside the other opinion
-- objects). The id is a server-generated 26-character Crockford Base32
-- ULID (full-repository convention). The prototype has no auth context,
-- so created_by comes from the request body and defaults to empty (same
-- convention as drill_runs). Timestamps follow the repository
-- convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS opinion_reviews (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL UNIQUE REFERENCES drill_runs(id) ON DELETE CASCADE,
    case_summary TEXT NOT NULL DEFAULT '',
    highlights   TEXT NOT NULL DEFAULT '',
    problems     TEXT NOT NULL DEFAULT '',
    lessons      TEXT NOT NULL DEFAULT '',
    suggestions  TEXT NOT NULL DEFAULT '',
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
