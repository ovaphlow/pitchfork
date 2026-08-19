-- 000013_drill_step_records.sql
-- Step execution records (演练步骤执行记录) of the scenario-simulation-drill
-- module: the execution state of one step within one drill run. A record
-- is keyed by the (run_id, step_id) pair (UNIQUE): the first PUT of a
-- pair creates the row, later PUTs update it in place (the service
-- preserves the id and created_at). The run foreign key cascades, so
-- deleting a run removes its step records (the in-memory store mirrors
-- this through DeleteStepRecordsByRun); the step foreign key carries no
-- cascade because steps are scenario-template rows that outlive any
-- single run. The status is one of 待执行 / 已执行 / 跳过 and defaults to
-- 待执行; action_note and performed_by default to ''; performed_at is
-- nullable (a step may be recorded without a timestamp). The prototype
-- has no auth context, so created_by comes from the request body and
-- defaults to empty (same convention as drill_runs). There is no
-- metadata column: the StepRecord model does not carry the JSONB
-- extension (unlike drill_runs, whose Run model does), so the column
-- set maps one-to-one to the model fields. Timestamps follow the
-- repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS drill_step_records (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    step_id      TEXT NOT NULL REFERENCES drill_scenario_steps(id),
    status       TEXT NOT NULL DEFAULT '待执行' CHECK (status IN ('待执行', '已执行', '跳过')),
    action_note  TEXT NOT NULL DEFAULT '',
    performed_by TEXT NOT NULL DEFAULT '',
    performed_at TIMESTAMPTZ,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, step_id)
);
