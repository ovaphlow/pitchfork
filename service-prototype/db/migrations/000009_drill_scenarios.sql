-- 000009_drill_scenarios.sql
-- Drill scenario templates (演练场景模板) of the scenario-simulation-drill
-- module (module 2 of the museum safety platform). A scenario template
-- carries a name, one of the four built-in categories (大客流聚集 /
-- 停电与基础设施 / 火灾 / 气象灾害), a simulated background, a lifecycle
-- status (defaults to 启用), an optional JSONB metadata extension and
-- the creator id. The prototype has no auth context, so created_by
-- comes from the request body and defaults to empty. Timestamps follow
-- the repository convention (created_at + updated_at). The steps and
-- assessment points of a template arrive with later migrations.

CREATE TABLE IF NOT EXISTS drill_scenarios (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    category   TEXT NOT NULL CHECK (category IN ('大客流聚集', '停电与基础设施', '火灾', '气象灾害')),
    background TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT '启用' CHECK (status IN ('启用', '停用')),
    metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
