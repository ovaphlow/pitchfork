-- 000015_drill_assessments.sql
-- Drill assessments (演练考核评估) of the scenario-simulation-drill module:
-- the score (0-100) and the comment for one assessment point (考核要点) of
-- one drill run. A row is keyed by the (run_id, point_id) pair (UNIQUE):
-- the first PUT of a pair creates the row, later PUTs update it in place
-- (the service preserves the id and created_at). The run foreign key
-- cascades, so deleting a run removes its assessments (the in-memory
-- store mirrors this through DeleteAssessmentsByRun); the point foreign
-- key carries no cascade because assessment points are scenario-template
-- rows that outlive any single run (same convention as
-- drill_step_records). score is required and must be between 0 and 100
-- (CHECK); comment defaults to ''. The prototype has no auth context, so
-- created_by comes from the request body and defaults to empty (same
-- convention as drill_runs and drill_step_records). There is no metadata
-- column: the Assessment model does not carry the JSONB extension, so
-- the column set maps one-to-one to the model fields. Timestamps follow
-- the repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS drill_assessments (
    id         TEXT PRIMARY KEY,
    run_id     TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    point_id   TEXT NOT NULL REFERENCES drill_assessment_points(id),
    score      INT NOT NULL CHECK (score >= 0 AND score <= 100),
    comment    TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, point_id)
);
