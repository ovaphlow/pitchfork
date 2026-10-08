-- =====================================================
-- 054 B4 — 家庭训练指导手册分发台账
-- Schema: nursing；号段 V400–V499（054 占用 V412）
--
-- 设计（054 §5.7 `ChildRehabHandout`、§11.2 Q10）：
--   - 手册**模板为常量**（`ChildRehabHandoutService.TEMPLATES`，与 V412 同批交付），
--     本表存**分发台账**：某儿童、某模板、某渠道、某时刻的分发快照（含 `content` 正文快照，
--     模板后续修订不影响历史台账）。
--   - 签收状态**不落独立列**：`acknowledged_at IS NULL` ⇒ 未签收，非空 ⇒ 已签收；
--     签收是**显式动作**（`PATCH /child-rehab-handouts/:id/acknowledge`），且**单向**
--     （已签收再签 → 409，由 `acknowledged_at IS NULL` 条件更新 + 行数判定兜底并发）。
--   - `channel` 未列入 §5.8 枚举白名单 → 自由文本，缺省「现场」（前端建议值：现场/微信/纸质）。
-- =====================================================

CREATE TABLE nursing.nursing_child_rehab_handouts (
    id               VARCHAR(32) PRIMARY KEY,
    patient_id       VARCHAR(32) NOT NULL,
    template_code    VARCHAR NOT NULL,                    -- 模板常量 code（ChildRehabHandoutService）
    template_name    VARCHAR NOT NULL,                    -- 分发时模板名快照
    content          TEXT NOT NULL,                       -- 分发正文快照
    channel          VARCHAR NOT NULL DEFAULT '现场',      -- 分发渠道（自由文本，缺省 现场）
    delivered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 分发时刻
    acknowledged_at  TIMESTAMPTZ,                         -- 签收时刻（NULL = 未签收）
    acknowledged_by  VARCHAR,                             -- 签收登记人（认证主体，042 口径）
    operator         VARCHAR NOT NULL,                    -- 分发人（认证主体，042 口径）
    metadata         JSONB,
    created_at       TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_child_rehab_handouts IS
    '家庭训练指导手册分发台账（054 B4）；签收状态由 acknowledged_at 派生，单向流转';

CREATE INDEX IF NOT EXISTS idx_child_rehab_handouts_patient
    ON nursing.nursing_child_rehab_handouts (patient_id, delivered_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_rehab_handouts_pending
    ON nursing.nursing_child_rehab_handouts (patient_id) WHERE acknowledged_at IS NULL;
