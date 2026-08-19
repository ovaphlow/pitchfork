-- 000024_evaluation_scores.sql
-- Evaluation scores (评估评分记录) of the comprehensive-evaluation module
-- (module 4): the three human scoring sources — 专家评分 (expert), 自评
-- (self) and 互评 (peer) — recorded for one evaluation indicator of one
-- drill run. score_type is CHECK-guarded to the three values; rater is
-- required (no default); target is required for 自评/互评 and forced to ''
-- for 专家评分 (both rules enforced by the service layer); score must be
-- between 0 and 100 (CHECK); comment and created_by default to ''. The
-- id is a server-generated 26-character Crockford Base32 ULID
-- (full-repository convention); timestamps follow the repository
-- convention (created_at + updated_at, now() defaults). There is no
-- metadata column: the Score model does not carry the JSONB extension,
-- so the column set maps one-to-one to the model fields.
--
-- An expert score is unique per (run_id, indicator_id): the partial
-- unique index mirrors the service-layer rule (a duplicate POST answers
-- 400 with the use-PUT hint; the same check runs on PUT excluding the
-- record itself), while 自评/互评 allow multiple records. The run foreign
-- key cascades, so deleting a run removes its scores (the in-memory
-- evaluation score store mirrors this through DeleteScoresByRun, hooked
-- into the drills service run-deletion cascade); the indicator foreign
-- key carries no cascade because indicators are dictionary rows that
-- outlive any single run — a referenced indicator is protected by the
-- service-layer score-ref checker (the DB keeps the FK as the last line
-- of defence).

CREATE TABLE IF NOT EXISTS evaluation_scores (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    indicator_id TEXT NOT NULL REFERENCES evaluation_indicators(id),
    score_type   TEXT NOT NULL CHECK (score_type IN ('专家评分', '自评', '互评')),
    rater        TEXT NOT NULL,
    target       TEXT NOT NULL DEFAULT '',
    score        INT NOT NULL CHECK (score >= 0 AND score <= 100),
    comment      TEXT NOT NULL DEFAULT '',
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS evaluation_scores_expert_unique
    ON evaluation_scores (run_id, indicator_id)
    WHERE score_type = '专家评分';
