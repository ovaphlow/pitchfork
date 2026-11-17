-- 0004_job_positions_open_window.sql — 招聘窗口自动停招
--
-- open_until 是当前招聘窗口的截止时间：首次发布 = NOW() + 14 天；停招后恢复 =
-- NOW() + 7 天。窗口到期不在后台任务里落状态，由 positions.Store 在读取/流转时
-- 把过期的「招聘中」惰性改为「已停招」，公开列表同时兜底过滤 open_until。

ALTER TABLE job_positions
    ADD COLUMN IF NOT EXISTS open_until TIMESTAMPTZ;

COMMENT ON COLUMN job_positions.open_until IS
    '当前招聘窗口截止时间；status = 招聘中 且 open_until <= NOW() 时自动停招；'
    '首次发布 +14 天，恢复招聘 +7 天';

ALTER TABLE job_positions DROP CONSTRAINT IF EXISTS job_positions_status_check;
ALTER TABLE job_positions ADD CONSTRAINT job_positions_status_check
    CHECK (status IN ('草稿','招聘中','已暂停','已停招','已关闭'));

-- 存量在招/暂停岗位按原发布时间补齐窗口；已超期的会在下次读取时转为已停招。
UPDATE job_positions
SET open_until = COALESCE(published_at, NOW()) + INTERVAL '14 days'
WHERE status IN ('招聘中', '已暂停') AND open_until IS NULL;

CREATE INDEX IF NOT EXISTS idx_job_positions_open_until
    ON job_positions (open_until) WHERE status = '招聘中';
