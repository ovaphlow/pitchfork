-- 000018_dispatch_orders.sql
-- Dispatch orders (调度指令) of the command-and-dispatch training module
-- (module 3): an order issued by the commander (指挥员) to a department,
-- group or individual during a drill run. An order belongs to exactly
-- one drill run and is deleted with it (ON DELETE CASCADE, mirroring
-- drill_sim_events and dispatch_sessions). title/content are required;
-- priority defaults to 普通 and must be one of 普通/紧急/特急; target_type
-- is required (no default, like the CHECK-guarded enums of the other
-- module-3 tables) and must be one of 部门/小组/个人; target_name is
-- required. status tracks the execution progress through the state
-- machine 待接收→已接收→执行中→已完成 (the service only allows adjacent
-- transitions) and defaults to 待接收; feedback records the execution
-- feedback and defaults to an empty string. deadline is an optional
-- required-completion time; issued_at is set by the service at creation
-- (an order is issued the moment it is created); completed_at is managed
-- by the service together with the status (set when the status becomes
-- 已完成, null otherwise). The id is a server-generated 26-character
-- Crockford Base32 ULID (full-repository convention). The prototype has
-- no auth context, so created_by comes from the request body and
-- defaults to empty (same convention as drill_runs, drill_sim_events
-- and dispatch_sessions). Timestamps follow the repository convention
-- (created_at + updated_at).

CREATE TABLE IF NOT EXISTS dispatch_orders (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    title        TEXT NOT NULL,
    content      TEXT NOT NULL,
    priority     TEXT NOT NULL DEFAULT '普通' CHECK (priority IN ('普通', '紧急', '特急')),
    target_type  TEXT NOT NULL CHECK (target_type IN ('部门', '小组', '个人')),
    target_name  TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT '待接收' CHECK (status IN ('待接收', '已接收', '执行中', '已完成')),
    feedback     TEXT NOT NULL DEFAULT '',
    deadline     TIMESTAMPTZ,
    issued_at    TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
