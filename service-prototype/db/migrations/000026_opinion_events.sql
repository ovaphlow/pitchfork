-- 000026_opinion_events.sql
-- Opinion event configuration (舆情事件配置) of the public-opinion-response
-- training module (module 5): the anchor object of the module — at most
-- one opinion event exists per drill run (run_id UNIQUE), carrying the
-- event background (event_name / subject / summary / occurred_at), the
-- opinion level (舆情等级: 高热 / 中热 / 低热) and the event-level
-- disposition state machine (监测中 -> 已预警 -> 已处置, the
-- 「舆情监测与预警」 training phase). The first PUT of a run creates the
-- row, later PUTs update it in place (the service preserves the id and
-- created_at). The run foreign key cascades, so deleting a run removes
-- its opinion event (the in-memory opinion store mirrors this through
-- DeleteByRun). The id is a server-generated 26-character Crockford
-- Base32 ULID (full-repository convention). event_name is required;
-- subject/summary are free strings defaulting to ''; occurred_at is an
-- optional TIMESTAMPTZ (null when unset); level defaults to 中热 and
-- status to 监测中, both guarded by CHECK constraints; metadata follows
-- the repository JSONB extension-field convention and defaults to an
-- empty object. The prototype has no auth context, so created_by comes
-- from the request body and defaults to empty (same convention as
-- drill_runs). Timestamps follow the repository convention (created_at
-- + updated_at).

CREATE TABLE IF NOT EXISTS opinion_events (
    id          TEXT PRIMARY KEY,
    run_id      TEXT NOT NULL UNIQUE REFERENCES drill_runs(id) ON DELETE CASCADE,
    event_name  TEXT NOT NULL,
    subject     TEXT NOT NULL DEFAULT '',
    summary     TEXT NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ,
    level       TEXT NOT NULL DEFAULT '中热' CHECK (level IN ('高热', '中热', '低热')),
    status      TEXT NOT NULL DEFAULT '监测中' CHECK (status IN ('监测中', '已预警', '已处置')),
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
