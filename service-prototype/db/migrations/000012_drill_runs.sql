-- 000012_drill_runs.sql
-- Drill runs (演练任务) of the scenario-simulation-drill module: the
-- execution of one drill scenario template. A run references exactly one
-- scenario and carries a required title, a lifecycle status (defaults to
-- 未开始) and the server-managed started_at / completed_at timestamps
-- (null until the state machine sets them). The state machine
-- 未开始 -> 进行中 -> 已完成/已终止 is enforced at the service layer; the
-- CHECK constraint guards the stored values. Metadata follows the
-- repository JSONB extension-field convention and defaults to an empty
-- object. The prototype has no auth context, so created_by comes from
-- the request body and defaults to empty. Timestamps follow the
-- repository convention (created_at + updated_at).
--
-- The scenario foreign key deliberately carries no ON DELETE CASCADE:
-- this card does not change the scenario deletion behaviour (deleting a
-- scenario that is still referenced stays in the existing scope). The
-- in-memory store implements the same rule (no cascade from scenarios
-- to runs); note that this differs from the DB semantics of the
-- scenario child tables (000010 / 000011 carry ON DELETE CASCADE for
-- steps and assessment points). The step records, sim events and
-- assessments of a run arrive with later migrations and cascade on run
-- deletion.

CREATE TABLE IF NOT EXISTS drill_runs (
    id           TEXT PRIMARY KEY,
    scenario_id  TEXT NOT NULL REFERENCES drill_scenarios(id),
    title        TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT '未开始' CHECK (status IN ('未开始', '进行中', '已完成', '已终止')),
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
