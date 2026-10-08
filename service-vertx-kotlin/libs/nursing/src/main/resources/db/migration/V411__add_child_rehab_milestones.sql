-- =====================================================
-- 054 B3 — 儿童康复里程碑（登记态落库 + 读时状态派生）
-- Schema: nursing；号段 V400–V499（054 占用 V411）
--
-- 设计（054 §5.7/§5.8、§11.2 Q10）：
--   - 本表只存**登记态**：某儿童某里程碑是否已达成（`achieved_at` + `status`）。
--   - 四态（**未达 / 临近 / 已达成 / 逾期**）是**读时派生**（按儿童月龄与里程碑期望月龄），
--     **不落库**（§5.8 明确「读时派生，不落库」），避免月龄推进后状态陈旧。
--   - 同一儿童同一里程碑至多一行：`POST` 为**幂等 upsert**（重复登记覆盖达成信息）。
--   - `patient_id` 直挂儿童（不经过服务周期）：里程碑是发育/康复目标，与周期解耦。
-- =====================================================

CREATE TABLE nursing.nursing_child_rehab_milestones (
    id                   VARCHAR(32) PRIMARY KEY,
    patient_id           VARCHAR(32) NOT NULL,
    milestone_code       VARCHAR NOT NULL,                  -- 常量表 code（ChildRehabMilestone）
    milestone_name       VARCHAR NOT NULL,                  -- 登记时快照（常量改名不影响历史）
    expected_age_months  INT,                               -- 期望达成月龄（可空）
    achieved_at          DATE,                              -- 达成日期（空 = 未达成）
    status               VARCHAR NOT NULL DEFAULT '未达成',  -- 登记态：未达成/已达成（与 achieved_at 同步）
    operator             VARCHAR NOT NULL,                  -- 登记人（认证主体，042 口径）
    metadata             JSONB,
    created_at           TIMESTAMPTZ DEFAULT now(),
    updated_at           TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE nursing.nursing_child_rehab_milestones IS
    '儿童康复里程碑登记（054 B3）；四态（未达/临近/已达成/逾期）为读时派生，不落库';

-- 同一儿童同一里程碑至多一行（POST 幂等 upsert 依赖此唯一约束）
CREATE UNIQUE INDEX IF NOT EXISTS uq_child_rehab_milestones_patient_code
    ON nursing.nursing_child_rehab_milestones (patient_id, milestone_code);
CREATE INDEX IF NOT EXISTS idx_child_rehab_milestones_patient
    ON nursing.nursing_child_rehab_milestones (patient_id);
