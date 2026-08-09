-- =====================================================
-- 护理模块 - 院内护理异常事件与班次交接闭环
-- Schema: nursing
-- 017 计划：异常事件（上报/追加处置/关闭）与班次交接（冻结快照/接班/补充）
-- 只新增四张事实表，不修改 V400–V406 的任何既有表、约束或数据。
-- =====================================================
SET search_path TO nursing, public;

-- ----------------------------------------------------------------------------
-- 1. 院内护理异常事件主事实
--    精确关联养老入住（encounter_id）与其唯一照护周期（period_id）；
--    reporter 来自认证主体；状态只允许 已上报/处理中/已关闭 前进式流转。
-- ----------------------------------------------------------------------------
CREATE TABLE nursing.nursing_incidents (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL, -- REFERENCES healthcare.encounters(id)（跨 schema 弱关联）
    period_id       VARCHAR(32) NOT NULL REFERENCES nursing.nursing_service_periods(id),
    incident_type   VARCHAR NOT NULL CHECK (incident_type IN (
                        '跌倒/坠床',
                        '走失',
                        '压疮',
                        '误吸/噎食',
                        '用药差错',
                        '感染暴露',
                        '其他'
                    )),
    severity        VARCHAR NOT NULL CHECK (severity IN ('一般', '较重', '严重')),
    status          VARCHAR NOT NULL DEFAULT '已上报' CHECK (status IN ('已上报', '处理中', '已关闭')),
    occurred_at     TIMESTAMPTZ NOT NULL,
    description     TEXT NOT NULL,
    reporter        VARCHAR NOT NULL,   -- 认证主体 subject_id
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按周期/发生时间和未关闭状态的最小索引
CREATE INDEX IF NOT EXISTS idx_incident_period_occurred
    ON nursing.nursing_incidents (period_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_incident_period_status_open
    ON nursing.nursing_incidents (period_id, status)
    WHERE status <> '已关闭';
CREATE INDEX IF NOT EXISTS idx_incident_encounter_occurred
    ON nursing.nursing_incidents (encounter_id, occurred_at DESC);

-- ----------------------------------------------------------------------------
-- 2. 异常事件处置/通知/观察/关闭追加事实
--    只允许 INSERT（服务层不提供任何 UPDATE/DELETE 入口）；
--    正文、认证主体、发生时间与通知结果均为可审计事实。
-- ----------------------------------------------------------------------------
CREATE TABLE nursing.nursing_incident_actions (
    id                  VARCHAR(32) PRIMARY KEY,
    incident_id         VARCHAR(32) NOT NULL REFERENCES nursing.nursing_incidents(id),
    action_type         VARCHAR NOT NULL CHECK (action_type IN ('上报', '处置', '通知', '观察', '关闭')),
    body                TEXT NOT NULL,
    actor               VARCHAR NOT NULL,   -- 认证主体 subject_id
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_party      VARCHAR,            -- 通知对象（仅通知类动作）
    notification_result VARCHAR,            -- 通知结果（仅通知类动作）
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_incident_action_incident
    ON nursing.nursing_incident_actions (incident_id, created_at ASC, id ASC);

-- ----------------------------------------------------------------------------
-- 3. 班次交接单头
--    事实单元 = 照护单元 + 上海业务日期 + 班次，永久唯一；
--    care_unit 由服务端从活动养老入住的 department 字段推导并验证，
--    客户端不提交、不覆盖；交班人/接班人来自认证主体。
-- ----------------------------------------------------------------------------
CREATE TABLE nursing.nursing_shift_handovers (
    id               VARCHAR(32) PRIMARY KEY,
    care_unit        VARCHAR NOT NULL,
    business_date    DATE NOT NULL,          -- 上海业务日期
    shift            VARCHAR NOT NULL CHECK (shift IN ('早班', '中班', '夜班')),
    handover_by      VARCHAR NOT NULL,       -- 交班人（认证主体）
    handed_over_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_by      VARCHAR,                -- 接班人（认证主体）
    received_at      TIMESTAMPTZ,
    status           VARCHAR NOT NULL DEFAULT '待接班' CHECK (status IN ('待接班', '已接班')),
    idempotency_key  VARCHAR NOT NULL,       -- 幂等键（重试比较）
    content_digest   VARCHAR NOT NULL,       -- 规范化请求摘要（同键同内容幂等）
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_nursing_shift_handover_scope UNIQUE (care_unit, business_date, shift)
);

CREATE INDEX IF NOT EXISTS idx_shift_handover_scope_date
    ON nursing.nursing_shift_handovers (business_date DESC, shift);

-- ----------------------------------------------------------------------------
-- 4. 班次交接事项（自动快照 + 手工/补充事项）
--    自动事项的来源关联（encounter/period/source_id）全部由服务端写入，
--    客户端不能伪造来源；手工事项来源关联为空。
-- ----------------------------------------------------------------------------
CREATE TABLE nursing.nursing_shift_handover_items (
    id             VARCHAR(32) PRIMARY KEY,
    handover_id    VARCHAR(32) NOT NULL REFERENCES nursing.nursing_shift_handovers(id),
    item_kind      VARCHAR NOT NULL CHECK (item_kind IN ('执行', '事件', '护理记录', '入住', '手工')),
    encounter_id   VARCHAR(32),   -- 自动事项必填；手工事项为空
    period_id      VARCHAR(32),   -- 自动事项必填；手工事项为空
    source_id      VARCHAR(32),   -- 来源表行 ID（执行/事件/护理记录）
    summary        TEXT NOT NULL,
    created_by     VARCHAR NOT NULL,   -- 自动事项为交班人，补充事项为补充人
    snapshot_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_shift_handover_item_handover
    ON nursing.nursing_shift_handover_items (handover_id, created_at ASC, id ASC);
CREATE INDEX IF NOT EXISTS idx_shift_handover_item_encounter
    ON nursing.nursing_shift_handover_items (encounter_id, handover_id);
