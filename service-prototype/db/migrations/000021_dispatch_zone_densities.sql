-- 000021_dispatch_zone_densities.sql
-- Dispatch zone densities (区域人流热力上报) of the command-and-dispatch
-- training module (module 3): the per-zone crowd-density reports of a
-- drill run that drive the command-center big-screen heat map. Field
-- personnel report the crowd density of each zone (zone_name is the
-- required zone name; people_count is the required non-negative head
-- count); the report is recorded the moment it arrives — reported_at is
-- set by the service at creation and refreshed on every update. A
-- report belongs to exactly one drill run and is deleted with it (ON
-- DELETE CASCADE, mirroring dispatch_sessions, dispatch_orders,
-- dispatch_department_reports and dispatch_messages; the in-memory
-- dispatch store mirrors this through DeleteZoneDensitiesByRun).
-- zone_name is required (no default); people_count is required and
-- CHECK-guarded to be non-negative. reported_at stays nullable so the
-- in-memory model mirrors the same optional instant shape as sent_at of
-- dispatch_messages, but the service always sets it at creation and
-- refresh on update, so it echoes non-null after creation. The id is a
-- server-generated 26-character Crockford Base32 ULID (full-repository
-- convention); created_by comes from the request body and defaults to
-- empty (same convention as the other module-3 tables). Timestamps
-- follow the repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS dispatch_zone_densities (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    zone_name    TEXT NOT NULL,
    people_count INT NOT NULL CHECK (people_count >= 0),
    reported_at  TIMESTAMPTZ,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
