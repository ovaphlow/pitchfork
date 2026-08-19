-- 000019_dispatch_department_reports.sql
-- Dispatch department reports (部门联动处置记录) of the
-- command-and-dispatch training module (module 3): the per-department
-- linkage-disposal record of a drill run. Five linkage departments
-- (联动部门) join the joint-disposal drill — 消防/公安/卫健/场馆应急组/其他 —
-- and at most one report exists per (run, department) pair
-- (UNIQUE (run_id, department)): the first PUT of a pair creates the
-- row, later PUTs update it in place (the service preserves the id and
-- created_at and only allows adjacent status transitions). status tracks
-- the linkage-disposal progress through the state machine
-- 未响应→已响应→已到位→处置中→已完成 (the service only allows adjacent
-- forward transitions) and defaults to 未响应; note records the disposal
-- description and defaults to an empty string; arrived_at is the
-- optional arrival time, passed through by the client and null by
-- default. The run foreign key cascades, so deleting a run removes its
-- department reports (the in-memory dispatch store mirrors this through
-- DeleteDepartmentsByRun). The id is a server-generated 26-character
-- Crockford Base32 ULID (full-repository convention). The prototype has
-- no auth context, so created_by comes from the request body and
-- defaults to empty (same convention as dispatch_sessions and
-- dispatch_orders). Timestamps follow the repository convention
-- (created_at + updated_at).

CREATE TABLE IF NOT EXISTS dispatch_department_reports (
    id         TEXT PRIMARY KEY,
    run_id     TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    department TEXT NOT NULL CHECK (department IN ('消防', '公安', '卫健', '场馆应急组', '其他')),
    status     TEXT NOT NULL DEFAULT '未响应' CHECK (status IN ('未响应', '已响应', '已到位', '处置中', '已完成')),
    note       TEXT NOT NULL DEFAULT '',
    arrived_at TIMESTAMPTZ,
    created_by TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, department)
);
