-- 000027_opinion_posts.sql
-- Opinion posts (舆情信息) of the public-opinion-response training module
-- (module 5): the simulated network public-opinion feed (模拟网络舆情
-- 信息流) of the 「舆情监测与预警」 training phase, listed newest-first in
-- the monitoring flow. Each row belongs to one drill run; source is the
-- origin platform (来源平台: 微博 / 抖音 / 新闻媒体 / 论坛 / 其他),
-- content is the post body (required), sentiment is the emotional
-- tendency (情感倾向: 负面 / 中性 / 正面), heat is the popularity value
-- (热度值 0–100), and warn_status is the one-way warning state machine
-- (预警状态: 未预警 -> 已预警; warned_at is set by the service when the
-- post is warned and stays null while the post is not warned). metadata
-- follows the repository JSONB extension-field convention and defaults
-- to an empty object. The run foreign key cascades, so deleting a run
-- removes its posts (the in-memory opinion store mirrors this through
-- DeleteByRun, which this card extends to clean the posts alongside the
-- opinion events). The id is a server-generated 26-character Crockford
-- Base32 ULID (full-repository convention). The prototype has no auth
-- context, so created_by comes from the request body and defaults to
-- empty (same convention as drill_runs). Timestamps follow the
-- repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS opinion_posts (
    id          TEXT PRIMARY KEY,
    run_id      TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    source      TEXT NOT NULL DEFAULT '微博' CHECK (source IN ('微博', '抖音', '新闻媒体', '论坛', '其他')),
    content     TEXT NOT NULL,
    sentiment   TEXT NOT NULL DEFAULT '负面' CHECK (sentiment IN ('负面', '中性', '正面')),
    heat        INT NOT NULL DEFAULT 0 CHECK (heat >= 0 AND heat <= 100),
    warn_status TEXT NOT NULL DEFAULT '未预警' CHECK (warn_status IN ('未预警', '已预警')),
    warned_at   TIMESTAMPTZ,
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
