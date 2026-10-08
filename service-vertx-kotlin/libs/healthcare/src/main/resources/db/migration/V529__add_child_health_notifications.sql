-- =====================================================
-- Healthcare — 儿保家长通报台账（V529，052 P4）
--
-- 背景：052 D6 决定消费 Nexus `messages` 能力（`POST /crate-api/shared/v1/messages`），
-- 不在 Aceso 重建消息栈；同时在儿保域写一条**通报台账**以便按儿童查询与追溯。
--
-- 口径：
--   1. 台账记录一次「通报家长」动作：儿童、异常类型与引用、监护人、渠道、
--      内容快照、Nexus `message_id`、状态、操作人。
--   2. `channel` 为预留字段：MVP 只接入「站内」（Nexus message），短信/微信未接入时
--      状态记「待接入」，**不得**宣称已送达（052 D6 / 裁定 4）。
--   3. `content` 为发送内容快照；payload 只带必要字段，不落身份证号（052 D10）。
--   4. `operator` 一律取认证主体，客户端不得提交。
--   5. 不重跑 jOOQ codegen：新表一律以 DSL.table/DSL.field 按名引用。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE child_health_notifications (
    id            VARCHAR(32) PRIMARY KEY,
    patient_id    VARCHAR(32) NOT NULL REFERENCES patients(id),
    alert_type    VARCHAR NOT NULL,                     -- 体温异常/心率异常/呼吸异常/生长异常/接种逾期/体检异常
    alert_ref     VARCHAR,                              -- 异常来源引用（体征记录 id / 测量 id / 疫苗:剂次 / 体检 id）
    guardian_name VARCHAR,                              -- 监护人姓名（快照）
    guardian_phone VARCHAR,                             -- 监护人电话（快照）
    channel       VARCHAR NOT NULL DEFAULT '站内',        -- 站内 / 短信 / 微信（后两者未接入）
    content       TEXT NOT NULL,                        -- 通报内容快照
    message_id    VARCHAR(32),                          -- Nexus messages.id；未发送成功为 NULL
    status        VARCHAR NOT NULL DEFAULT '已发送',      -- 已发送 / 待接入 / 发送失败
    operator      VARCHAR NOT NULL,                     -- 操作人（认证主体），客户端不得提交
    metadata      JSONB,
    created_at    TIMESTAMPTZ DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE child_health_notifications IS '儿保家长通报台账：052 P4，消费 Nexus messages 并留痕；渠道未接入时状态为「待接入」';
COMMENT ON COLUMN child_health_notifications.alert_type IS '异常类型中文枚举：体温异常/心率异常/呼吸异常/生长异常/接种逾期/体检异常/健康指导（后者为 P5 指导转通报）';
COMMENT ON COLUMN child_health_notifications.channel IS '渠道：站内/短信/微信；MVP 仅「站内」接入 Nexus message，其余记「待接入」，不得宣称已送达';
COMMENT ON COLUMN child_health_notifications.message_id IS 'Nexus messages.id；未成功发送时为 NULL，与 status 一致';
COMMENT ON COLUMN child_health_notifications.status IS '状态中文枚举：已发送/待接入/发送失败';

CREATE INDEX IF NOT EXISTS idx_child_health_notifications_patient
    ON child_health_notifications(patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_health_notifications_alert
    ON child_health_notifications(patient_id, alert_type, alert_ref);
