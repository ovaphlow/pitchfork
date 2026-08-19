-- 000020_dispatch_messages.sql
-- Dispatch messages (即时通讯消息) of the command-and-dispatch training
-- module (module 3): the simulated real-time communication stream
-- between the command center and the field personnel during a drill
-- run (多端联动). A message belongs to exactly one drill run and is
-- deleted with it (ON DELETE CASCADE, mirroring drill_sim_events,
-- dispatch_sessions, dispatch_orders and dispatch_department_reports).
-- sender_type is the fixed two-sided business enum (指挥中心/现场人员) and
-- is required (no default, like the CHECK-guarded enums of the other
-- module-3 tables); sender_name is the display name of the sender and
-- defaults to an empty string (optional, the prototype has no auth
-- context); content is the message text and is required. sent_at is set
-- by the service at creation (a message is sent the moment it is
-- created) and the column stays nullable so the in-memory model mirrors
-- the same optional instant shape as issued_at of dispatch_orders.
-- Messages are immutable: there is no update path, so the table carries
-- no status/update semantics beyond the repository timestamp
-- convention. The id is a server-generated 26-character Crockford
-- Base32 ULID (full-repository convention); created_by comes from the
-- request body and defaults to empty (same convention as drill_runs,
-- drill_sim_events, dispatch_sessions, dispatch_orders and
-- dispatch_department_reports).

CREATE TABLE IF NOT EXISTS dispatch_messages (
    id          TEXT PRIMARY KEY,
    run_id      TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    sender_type TEXT NOT NULL CHECK (sender_type IN ('指挥中心', '现场人员')),
    sender_name TEXT NOT NULL DEFAULT '',
    content     TEXT NOT NULL,
    sent_at     TIMESTAMPTZ,
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
