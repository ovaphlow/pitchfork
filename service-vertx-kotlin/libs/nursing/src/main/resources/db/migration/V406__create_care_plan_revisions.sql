-- 护理照护计划修订关系表：记录复评驱动的计划版本切换
-- 只新增本表，不修改 V400–V405 的任何既有表、约束或数据。
CREATE TABLE nursing.nursing_care_plan_revisions (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) NOT NULL REFERENCES nursing.nursing_service_periods(id),
    encounter_id    VARCHAR(32) NOT NULL, -- REFERENCES healthcare.encounters(id)（跨 schema 弱关联）
    assessment_id   VARCHAR(32) NOT NULL REFERENCES nursing.nursing_assessments(id),
    previous_plan_id VARCHAR(32) REFERENCES nursing.nursing_plans(id),
    new_plan_id     VARCHAR(32) NOT NULL UNIQUE REFERENCES nursing.nursing_plans(id),
    revision_no     INT NOT NULL CHECK (revision_no > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_nursing_care_plan_revisions_period_revision UNIQUE (period_id, revision_no)
);

CREATE INDEX IF NOT EXISTS idx_care_plan_revision_period
    ON nursing.nursing_care_plan_revisions(period_id);
CREATE INDEX IF NOT EXISTS idx_care_plan_revision_assessment
    ON nursing.nursing_care_plan_revisions(assessment_id);
CREATE INDEX IF NOT EXISTS idx_care_plan_revision_previous_plan
    ON nursing.nursing_care_plan_revisions(previous_plan_id);
