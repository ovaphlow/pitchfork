-- 000029_opinion_complaints.sql
-- Opinion complaints (投诉处理记录) of the public-opinion-response
-- training module (module 5): the visitor complaint tickets (观众投诉
-- 工单) of the 「投诉处理」 training phase, listed in intake order
-- (created_at ASC, id ASC). Each row belongs to one drill run;
-- complainant is the complaining visitor (required), channel is the
-- complaint channel (投诉渠道: 现场 / 电话 / 网络留言 / 12345转办 /
-- 其他), complaint_type focuses on the card scenarios (投诉类型:
-- 入馆受阻 / 参观受限, plus 服务态度 / 设施问题 / 其他), content is the
-- complaint body (required), and status is the forward-only handling
-- state machine (投诉状态: 待受理 -> 处理中 -> 已办结; closed_at is set
-- by the service at the 已办结 step and stays null while the complaint
-- is not closed). handling (安抚疏导措施) and handler (处理人) record
-- the soothing-guidance measures and the responsible handler. metadata
-- follows the repository JSONB extension-field convention and defaults
-- to an empty object. The run foreign key cascades, so deleting a run
-- removes its complaints (the in-memory opinion store mirrors this
-- through DeleteByRun, which this card extends to clean the complaints
-- alongside the other opinion objects). The id is a server-generated
-- 26-character Crockford Base32 ULID (full-repository convention). The
-- prototype has no auth context, so created_by comes from the request
-- body and defaults to empty (same convention as drill_runs).
-- Timestamps follow the repository convention (created_at + updated_at).

CREATE TABLE IF NOT EXISTS opinion_complaints (
    id             TEXT PRIMARY KEY,
    run_id         TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE,
    complainant    TEXT NOT NULL,
    channel        TEXT NOT NULL DEFAULT '现场' CHECK (channel IN ('现场', '电话', '网络留言', '12345转办', '其他')),
    complaint_type TEXT NOT NULL DEFAULT '入馆受阻' CHECK (complaint_type IN ('入馆受阻', '参观受限', '服务态度', '设施问题', '其他')),
    content        TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT '待受理' CHECK (status IN ('待受理', '处理中', '已办结')),
    handling       TEXT NOT NULL DEFAULT '',
    handler        TEXT NOT NULL DEFAULT '',
    closed_at      TIMESTAMPTZ,
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by     TEXT NOT NULL DEFAULT '',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
