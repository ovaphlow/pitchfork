-- =====================================================
-- Healthcare — 儿保日常健康指导记录（V530，052 P5）
--
-- 背景：052 D7 决定「模板常量 + 开展记录」：指导模板（月龄段 × 主题）为前端
-- `lib/guidance.ts` 的静态常量，后端只存每次指导的开展记录，可一键转家长通报。
--
-- 口径：
--   1. `template_code` 指向前端常量表的模板；`age_band`/`topic`/`content` 为开展时的快照，
--      模板后续调整不影响历史记录（事实快照）。
--   2. `channel` 与 V529 同义：站内/短信/微信；未接入渠道记「站内」或「待接入」。
--   3. `operator` 一律取认证主体，客户端不得提交。
--   4. 不重跑 jOOQ codegen：新表一律以 DSL.table/DSL.field 按名引用。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE child_health_guidance_records (
    id            VARCHAR(32) PRIMARY KEY,
    patient_id    VARCHAR(32) NOT NULL REFERENCES patients(id),
    template_code VARCHAR NOT NULL,                     -- 指导模板编码（前端 lib/guidance.ts 常量）
    age_band      VARCHAR,                              -- 月龄段快照（0-6月 / 7-12月 / 13-24月 / 25-60月）
    topic         VARCHAR,                              -- 主题快照（喂养/睡眠/口腔/安全/发育促进/疾病预防）
    content       TEXT NOT NULL,                        -- 指导内容（可编辑后的最终文本）
    delivered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),   -- 开展时间
    channel       VARCHAR NOT NULL DEFAULT '站内',        -- 站内 / 短信 / 微信（后两者未接入）
    operator      VARCHAR NOT NULL,                     -- 操作人（认证主体），客户端不得提交
    metadata      JSONB,
    created_at    TIMESTAMPTZ DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE child_health_guidance_records IS '儿保日常健康指导开展记录：052 P5；模板为前端常量，本表只存开展事实快照';
COMMENT ON COLUMN child_health_guidance_records.template_code IS '指导模板编码，与前端 lib/guidance.ts 的 GUIDANCE_TEMPLATES 同源';
COMMENT ON COLUMN child_health_guidance_records.age_band IS '月龄段快照：0-6月/7-12月/13-24月/25-60月';
COMMENT ON COLUMN child_health_guidance_records.content IS '开展时最终指导内容快照（模板调整不影响历史）';

CREATE INDEX IF NOT EXISTS idx_child_health_guidance_records_patient
    ON child_health_guidance_records(patient_id, delivered_at DESC);
