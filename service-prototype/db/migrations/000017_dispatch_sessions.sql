-- 000017_dispatch_sessions.sql
-- Dispatch command sessions (指挥调度会话配置) of the command-and-dispatch
-- training module (module 3): the per-run configuration of how a drill
-- run is exercised — the training mode (实训方式: 桌面推演 / 实战演练 /
-- 远程协同), the main venue (主场馆) and the joint venues (联训场馆, used
-- by remote multi-venue joint drills). At most one session exists per
-- drill run (run_id UNIQUE): the first PUT of a run creates the row,
-- later PUTs update it in place (the service preserves the id and
-- created_at). The run foreign key cascades, so deleting a run removes
-- its session (the in-memory dispatch store mirrors this through
-- DeleteSessionsByRun). The id is a server-generated 26-character
-- Crockford Base32 ULID (full-repository convention). mode defaults to
-- 实战演练 and the CHECK constraint guards the stored values; main_venue
-- is a free string (empty is legal); joint_venues and metadata follow
-- the repository JSONB extension-field convention and default to an
-- empty array / empty object. The prototype has no auth context, so
-- created_by comes from the request body and defaults to empty (same
-- convention as drill_runs). Timestamps follow the repository convention
-- (created_at + updated_at).

CREATE TABLE IF NOT EXISTS dispatch_sessions (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL UNIQUE REFERENCES drill_runs(id) ON DELETE CASCADE,
    mode         TEXT NOT NULL DEFAULT '实战演练' CHECK (mode IN ('桌面推演', '实战演练', '远程协同')),
    main_venue   TEXT NOT NULL DEFAULT '',
    joint_venues JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
