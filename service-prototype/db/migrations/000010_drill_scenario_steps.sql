-- 000010_drill_scenario_steps.sql
-- Drill scenario steps (演练流程步骤) of the scenario-simulation-drill
-- module. A step belongs to exactly one scenario template; the foreign
-- key cascades so deleting a scenario removes its steps. Each step
-- carries a sort order (defaults to 0), a required title and a
-- description (defaults to ''). The prototype has no auth context, so
-- created_by comes from the request body and defaults to empty.
-- Timestamps follow the repository convention (created_at + updated_at).
-- There is no metadata column: the drills models do not carry the JSONB
-- extension for steps.

CREATE TABLE IF NOT EXISTS drill_scenario_steps (
    id          TEXT PRIMARY KEY,
    scenario_id TEXT NOT NULL REFERENCES drill_scenarios(id) ON DELETE CASCADE,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
