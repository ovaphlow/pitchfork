-- 000008_exam_records.sql
-- Online exam records (在线考核记录) of the theory-training module. One
-- row is opened by POST /exam-records (start_time is set then) and
-- finished by POST /exam-records/{id}/submit (end_time/score/passed are
-- written then; before submission they stay NULL). answers_snapshot is
-- the read-only, self-contained exam snapshot taken at opening: the
-- paper id, the pass score and every question of the paper with its
-- standard answer, so submission grading never reads the paper or the
-- question bank again. employee_id is a plain dimension (the prototype
-- has no employee master data) validated as a 26-character ULID;
-- paper_id references exam_papers and cascades on paper deletion.
-- metadata is the repository JSONB extension field echoed verbatim
-- (defaults to an empty object); created_by comes from the request body
-- and defaults to empty. Timestamps follow the repository convention
-- (created_at + updated_at) and are managed by the service layer.

CREATE TABLE IF NOT EXISTS exam_records (
    id               TEXT PRIMARY KEY,
    employee_id      TEXT NOT NULL,
    paper_id         TEXT NOT NULL REFERENCES exam_papers(id) ON DELETE CASCADE,
    start_time       TIMESTAMPTZ NOT NULL,
    end_time         TIMESTAMPTZ,
    score            INTEGER,
    passed           BOOLEAN,
    answers_snapshot JSONB NOT NULL,
    metadata         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by       TEXT NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
