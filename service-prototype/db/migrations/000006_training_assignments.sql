-- 000006_training_assignments.sql
-- Training task assignments (培训任务指派) of the theory-training module.
-- An assignment links a course to a set of training targets: it is
-- assigned either 手动指派 (manually) or 自动触发 (auto-triggered by a
-- rule carried in trigger_rule), and targets 用户/岗位/部门 through
-- target_ids. trigger_rule is an optional JSONB extension (defaults to an
-- empty object) that is required to be a JSON object when provided;
-- deadline is an optional RFC3339 timestamp (empty string means unset).
-- The prototype has no auth context, so created_by comes from the request
-- body and defaults to empty. Timestamps follow the repository convention
-- and are managed by the service layer; deleting a course cascades to its
-- assignments.

CREATE TABLE IF NOT EXISTS training_assignments (
    id           TEXT PRIMARY KEY,
    course_id    TEXT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    assign_type  TEXT NOT NULL CHECK (assign_type IN ('手动指派', '自动触发')),
    trigger_rule JSONB NOT NULL DEFAULT '{}'::jsonb,
    deadline     TEXT NOT NULL DEFAULT '',
    target_type  TEXT NOT NULL CHECK (target_type IN ('用户', '岗位', '部门')),
    target_ids   JSONB NOT NULL,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
