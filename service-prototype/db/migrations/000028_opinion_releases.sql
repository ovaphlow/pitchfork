-- 000028_opinion_releases.sql
-- Opinion releases (信息发布记录) of the public-opinion-response training
-- module (module 5): the situation-statement publication records (情况说明
-- 发布记录) of the 「信息发布」 training phase, listed newest-first in the
-- release flow. Each row belongs to one drill run; channel is the release
-- channel (发布渠道: 官方渠道 官网公告/微信公众号/微博官方号 + 主流媒体
-- 新闻媒体通稿, in which case media_name carries the media name),
-- title/content are the release headline and body (both required),
-- status is the forward-only publication state machine (发布状态: 草稿 ->
-- 待审核 -> 已发布 -> 已撤回; 已撤回 is terminal; published_at is set by
-- the service when the release is published and stays null otherwise).
-- metadata follows the repository JSONB extension-field convention and
-- defaults to an empty object. The run foreign key cascades, so deleting
-- a run removes its releases (the in-memory opinion store mirrors this
-- through DeleteByRun, which cleans the releases alongside the opinion
-- events and posts). The id is a server-generated 26-character Crockford
-- Base32 ULID (full-repository convention). The prototype has no auth
-- context, so created_by comes from the request body and defaults to
-- empty (same convention as drill_runs). Timestamps follow the
-- repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS opinion_releases (
    id           TEXT PRIMARY KEY,
    run_id       TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    channel      TEXT NOT NULL DEFAULT '官网公告' CHECK (channel IN ('官网公告', '微信公众号', '微博官方号', '新闻媒体通稿')),
    title        TEXT NOT NULL,
    content      TEXT NOT NULL,
    media_name   TEXT NOT NULL DEFAULT '',
    status       TEXT NOT NULL DEFAULT '草稿' CHECK (status IN ('草稿', '待审核', '已发布', '已撤回')),
    published_at TIMESTAMPTZ,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by   TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
