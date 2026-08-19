-- 000004_questions.sql
-- Question bank of the theory-training module. A question carries a type
-- (单选/多选/判断/填空), a difficulty on a 1-5 scale, optional tags, the
-- stem content, the options, the answer (shape depends on the type), an
-- optional explanation, an optional JSONB metadata extension and the
-- creator id. content/options/answer/explanation are JSONB columns; the
-- prototype API models them as string / string array / string or string
-- array / string. The prototype has no auth context, so created_by comes
-- from the request body and defaults to empty.

CREATE TABLE IF NOT EXISTS questions (
    id          TEXT PRIMARY KEY,
    type        TEXT NOT NULL CHECK (type IN ('单选', '多选', '判断', '填空')),
    difficulty  INT NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    tags        JSONB NOT NULL DEFAULT '[]'::jsonb,
    content     JSONB NOT NULL,
    options     JSONB NOT NULL DEFAULT '[]'::jsonb,
    answer      JSONB NOT NULL,
    explanation JSONB NOT NULL DEFAULT '""'::jsonb,
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
