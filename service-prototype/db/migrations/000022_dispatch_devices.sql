-- 000022_dispatch_devices.sql
-- Dispatch devices (设备运行状态上报) of the command-and-dispatch training
-- module (module 3): the running-status reports of the simulated venue
-- devices of a drill run (device_type is one of
-- 供配电/消防/安防/电梯/空调通风/广播通信/其他 and device_name is the
-- required device name). The reports drive the device-monitoring area of
-- the command-center big screen, where 告警/离线 devices are highlighted;
-- the feeds of the other systems (IoT device reporting) appear as
-- simulated data. A report belongs to exactly one drill run and is
-- deleted with it (ON DELETE CASCADE, mirroring dispatch_sessions,
-- dispatch_orders, dispatch_department_reports, dispatch_messages and
-- dispatch_zone_densities; the in-memory dispatch store mirrors this
-- through DeleteDevicesByRun). device_name is required (no default);
-- device_type is required (no default, like the CHECK-guarded enums of
-- the other module-3 tables) and must be one of
-- 供配电/消防/安防/电梯/空调通风/广播通信/其他; status defaults to 正常
-- and must be one of 正常/告警/离线; note carries the fault description
-- and defaults to an empty string. The id is a server-generated
-- 26-character Crockford Base32 ULID (full-repository convention);
-- created_by comes from the request body and defaults to empty (same
-- convention as the other module-3 tables). Timestamps follow the
-- repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS dispatch_devices (
    id          TEXT PRIMARY KEY,
    run_id      TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    device_type TEXT NOT NULL CHECK (device_type IN ('供配电', '消防', '安防', '电梯', '空调通风', '广播通信', '其他')),
    status      TEXT NOT NULL DEFAULT '正常' CHECK (status IN ('正常', '告警', '离线')),
    note        TEXT NOT NULL DEFAULT '',
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
