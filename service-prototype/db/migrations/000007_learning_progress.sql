-- 000007_learning_progress.sql
-- Learning progress (学习进度) of the theory-training module. One row
-- exists per (assignment, employee, chapter) triple: the unique key
-- backs the upsert semantics of the progress API — the first report of a
-- chapter creates the row, later reports update it in place. The
-- prototype has no employee master data, so employee_id is a plain
-- progress dimension and is never validated. progress_percent is the
-- reported 0-100 completion share; status is derived server-side
-- (学习中 until the chapter is completed at 100 percent or by the
-- assignment complete action) and never accepted as input; detail is an
-- optional JSONB extension echoed verbatim (defaults to an empty
-- object). started_at is set on the first report only; completed_at is
-- set when a chapter is completed and never reverted. Timestamps follow
-- the repository convention and are managed by the service layer;
-- deleting an assignment or a chapter cascades to its progress rows.

CREATE TABLE IF NOT EXISTS learning_progress (
    id               TEXT PRIMARY KEY,
    assignment_id    TEXT NOT NULL REFERENCES training_assignments(id) ON DELETE CASCADE,
    employee_id      TEXT NOT NULL,
    chapter_id       TEXT NOT NULL REFERENCES course_chapters(id) ON DELETE CASCADE,
    progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    status           TEXT NOT NULL DEFAULT '学习中' CHECK (status IN ('学习中', '已完成')),
    detail           JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assignment_id, employee_id, chapter_id)
);
