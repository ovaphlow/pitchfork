-- 000011_drill_assessment_points.sql
-- Drill assessment points (考核要点模板) of the scenario-simulation-drill
-- module. A point belongs to exactly one scenario template; the foreign
-- key cascades so deleting a scenario removes its points. Each point
-- carries a required title and a description (defaults to ''). The
-- prototype has no auth context, so created_by comes from the request
-- body and defaults to empty. Timestamps follow the repository
-- convention (created_at + updated_at). There is no metadata column:
-- the drills models do not carry the JSONB extension for points.

CREATE TABLE IF NOT EXISTS drill_assessment_points (
    id          TEXT PRIMARY KEY,
    scenario_id TEXT NOT NULL REFERENCES drill_scenarios(id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
