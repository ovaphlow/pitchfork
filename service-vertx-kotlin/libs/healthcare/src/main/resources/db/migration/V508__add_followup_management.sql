-- =====================================================
-- Healthcare — 随访管理 (Followup)
-- 养老/福利院方向：离院老人回访 + 在院慢病老人定期随访
-- Schema: healthcare
-- 设计原则：
--   1. 以 patients + encounters(ELDERLY_CARE) 锚定随访对象归属，跨对象伪造一律拒绝
--   2. 随访计划状态机由应用层管控：待随访/已完成/已取消；「已逾期」查询时计算，不落库
--   3. 随访记录只增不改（审计），错误走新增更正记录
--   4. 类型字段不加 CHECK 约束，枚举中文值由应用层白名单管控
-- =====================================================

SET search_path TO healthcare, public;

-- ----------------------------------------------------------------------------
-- 1. 随访计划表
--    预先安排的随访任务：对象（patient + 入住周期 encounter 锚定）、类型、
--    计划日期、方式、责任人（认证主体）。
-- ----------------------------------------------------------------------------
CREATE TABLE followup_plans (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    followup_type   VARCHAR NOT NULL,                 -- 出院后随访 / 慢病随访 / 常规电话随访
    planned_date    DATE NOT NULL,                    -- 计划随访日
    planned_way     VARCHAR NOT NULL DEFAULT '电话',    -- 计划方式: 电话 / 上门 / 门诊
    assignee        VARCHAR NOT NULL,                 -- 责任人（认证主体）
    status          VARCHAR NOT NULL DEFAULT '待随访',  -- 待随访 / 已完成 / 已取消（已逾期查询时计算）
    completed_at    TIMESTAMPTZ,                      -- 实际完成时间（记录随访完成计划时回填实际随访时间）
    cancel_reason   TEXT,                             -- 取消原因（取消时必须填写）
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE followup_plans IS '随访计划：预先安排的随访任务（养老/福利院）';
COMMENT ON COLUMN followup_plans.followup_type IS '随访类型，中文枚举：出院后随访/慢病随访/常规电话随访';
COMMENT ON COLUMN followup_plans.planned_way IS '计划随访方式，中文枚举：电话/上门/门诊，默认电话';
COMMENT ON COLUMN followup_plans.assignee IS '责任人，来自认证主体，客户端不得提交';
COMMENT ON COLUMN followup_plans.status IS '状态：待随访/已完成/已取消；已逾期由查询计算不落库';
COMMENT ON COLUMN followup_plans.completed_at IS '实际完成时间，完成时回填随访记录的实际随访时间';

CREATE INDEX IF NOT EXISTS idx_followup_plans_status_date ON followup_plans(status, planned_date);
CREATE INDEX IF NOT EXISTS idx_followup_plans_patient ON followup_plans(patient_id);
CREATE INDEX IF NOT EXISTS idx_followup_plans_encounter ON followup_plans(encounter_id);

-- ----------------------------------------------------------------------------
-- 2. 随访记录表
--    一次实际随访的事实：时间、方式、对象、状况、测量值、指导内容、结果。
--    写入后不可编辑、不可删除（审计）。
-- ----------------------------------------------------------------------------
CREATE TABLE followup_records (
    id                  VARCHAR(32) PRIMARY KEY,
    plan_id             VARCHAR(32) REFERENCES followup_plans(id), -- 可空：无计划的临时随访
    patient_id          VARCHAR(32) NOT NULL REFERENCES patients(id),
    encounter_id        VARCHAR(32) NOT NULL REFERENCES encounters(id),
    followup_type       VARCHAR NOT NULL,             -- 出院后随访 / 慢病随访 / 常规电话随访
    followup_way        VARCHAR NOT NULL DEFAULT '电话', -- 电话 / 上门 / 门诊
    followup_date       TIMESTAMPTZ NOT NULL DEFAULT now(), -- 实际随访时间
    contact_object      VARCHAR,                      -- 随访对象（老人/家属姓名）
    condition_summary   TEXT,                         -- 主诉/状况描述
    vitals              JSONB DEFAULT '{}',           -- 生命体征: 收缩压/舒张压/心率/血糖/体温
    guidance            TEXT,                         -- 用药/康复/饮食指导
    result              VARCHAR NOT NULL,             -- 正常 / 异常 / 需复访 / 需转诊
    next_followup_date  DATE,                         -- 建议下次随访日期
    operator            VARCHAR NOT NULL,             -- 记录人（认证主体），客户端不得提交
    metadata            JSONB,
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE followup_records IS '随访记录：一次实际随访的事实（只增不改，可审计）';
COMMENT ON COLUMN followup_records.vitals IS '生命体征测量值 JSON: {systolic, diastolic, heart_rate, blood_glucose, temperature}';
COMMENT ON COLUMN followup_records.result IS '随访结果，中文枚举：正常/异常/需复访/需转诊';
COMMENT ON COLUMN followup_records.operator IS '记录人，来自认证主体，客户端不得提交';

CREATE INDEX IF NOT EXISTS idx_followup_records_patient ON followup_records(patient_id);
CREATE INDEX IF NOT EXISTS idx_followup_records_plan ON followup_records(plan_id);
CREATE INDEX IF NOT EXISTS idx_followup_records_date ON followup_records(followup_date DESC);
