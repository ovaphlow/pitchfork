-- 000014_drill_sim_events.sql
-- Simulated events (演练模拟事件) of the scenario-simulation-drill module:
-- the UI stand-in for hardware sensors and external system feeds (crowd
-- density over threshold, power alarm, smoke detector, weather warning
-- or any other scenario). An event belongs to exactly one drill run and
-- is deleted with it (ON DELETE CASCADE, mirroring drill_step_records).
-- The event type must be one of the five built-in values; the service
-- additionally checks that the type matches the scenario category of
-- the owning run (或 其他 任意场景可用). The status is 已触发 / 已处置 and
-- defaults to 已触发. payload carries the mock sensor data (JSONB,
-- defaults to an empty object). triggered_at is set by the service at
-- creation and handled_at is managed by the service together with the
-- status; both are nullable. The prototype has no auth context, so
-- created_by comes from the request body and defaults to empty (same
-- convention as drill_runs and drill_step_records). There is no
-- metadata column: the SimEvent model does not carry the JSONB
-- extension, so the column set maps one-to-one to the model fields.
-- Timestamps follow the repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS drill_sim_events (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    event_type   TEXT NOT NULL CHECK (event_type IN ('客流密度超阈值', '供配电异常报警', '烟感探测器触发', '气象预警接收', '其他')),
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    status       TEXT NOT NULL DEFAULT '已触发' CHECK (status IN ('已触发', '已处置')),
    triggered_at TIMESTAMPTZ,
    handled_at   TIMESTAMPTZ,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
