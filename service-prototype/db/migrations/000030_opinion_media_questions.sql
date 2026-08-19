-- 000030_opinion_media_questions.sql
-- Opinion media questions (媒体问答记录) of the public-opinion-response
-- training module (module 5): the simulated press-conference Q&A records
-- (模拟新闻发布会问答记录) of the 「媒体沟通」 training phase, listed in
-- question order (created_at ASC, id ASC) during the press conference.
-- Each row belongs to one drill run; media_name is the media outlet
-- (媒体名称, required), reporter is the journalist name (记者, default ''),
-- question is the asked question (提问内容, required), question_type is
-- the question kind (问题类型: 事实类 / 质疑类 / 尖锐类), answer is the
-- trainee's reply (回答内容, '' until answered) and status is the
-- one-way answering state machine (回答状态: 未回答 -> 已回答; answered_at
-- is set by the service at the transition and stays null while the
-- question is not answered). metadata follows the repository JSONB
-- extension-field convention and defaults to an empty object. The run
-- foreign key cascades, so deleting a run removes its media questions
-- (the in-memory opinion store mirrors this through DeleteByRun, which
-- this card extends to clean the media questions alongside the opinion
-- events, posts and releases). The id is a server-generated
-- 26-character Crockford Base32 ULID (full-repository convention). The
-- prototype has no auth context, so created_by comes from the request
-- body and defaults to empty (same convention as drill_runs).
-- Timestamps follow the repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS opinion_media_questions (
    id            TEXT PRIMARY KEY,
    run_id        TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    media_name    TEXT NOT NULL,
    reporter      TEXT NOT NULL DEFAULT '',
    question      TEXT NOT NULL,
    question_type TEXT NOT NULL DEFAULT '事实类' CHECK (question_type IN ('事实类', '质疑类', '尖锐类')),
    answer        TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL DEFAULT '未回答' CHECK (status IN ('未回答', '已回答')),
    answered_at   TIMESTAMPTZ,
    metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by    TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
