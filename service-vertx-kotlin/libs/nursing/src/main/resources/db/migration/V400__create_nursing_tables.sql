-- =====================================================
-- 护理模块 - 核心表结构
-- 涵盖机构护理与家庭护理
-- Schema: nursing
-- =====================================================

CREATE SCHEMA IF NOT EXISTS nursing;
SET search_path TO nursing, public;

-- 护理服务周期表：家庭护理等场景的核心聚合根
CREATE TABLE nursing_service_periods (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL, -- REFERENCES patients(id) (healthcare schema)
    service_type    VARCHAR NOT NULL CHECK (service_type IN (
                        'HOME_CARE',
                        'COMMUNITY_CARE',
                        'HOSPICE'
                    )),
    start_date      DATE NOT NULL,
    end_date        DATE,
    coordinator     VARCHAR,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','COMPLETED','CANCELLED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- 护理评估表
CREATE TABLE nursing_assessments (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32), -- REFERENCES encounters(id) (healthcare schema)
    period_id       VARCHAR(32) REFERENCES nursing_service_periods(id),
    assess_type     VARCHAR NOT NULL CHECK (assess_type IN (
                        'ADMISSION',
                        'FALL_RISK',
                        'PRESSURE_SORE',
                        'PAIN',
                        'BARTHEL',
                        'NUTRITION',
                        'HOME_ENVIRONMENT',
                        'OTHER'
                    )),
    assess_date     DATE NOT NULL,
    assessor        VARCHAR,
    total_score     NUMERIC(5,1),
    result_level    VARCHAR,
    detail          JSONB,
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT chk_assess_ref CHECK (encounter_id IS NOT NULL OR period_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_assess_encounter ON nursing_assessments(encounter_id);
CREATE INDEX IF NOT EXISTS idx_assess_period ON nursing_assessments(period_id);

-- 护理计划表
CREATE TABLE nursing_plans (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) NOT NULL REFERENCES nursing_service_periods(id),
    encounter_id    VARCHAR(32), -- REFERENCES encounters(id) (healthcare schema)
    plan_name       VARCHAR NOT NULL,
    goals           TEXT,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','DISCONTINUED')),
    created_by      VARCHAR,
    start_date      DATE,
    end_date        DATE,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_plan_period ON nursing_plans(period_id);

-- 护理计划措施明细表
CREATE TABLE nursing_plan_items (
    id              VARCHAR(32) PRIMARY KEY,
    plan_id         VARCHAR(32) NOT NULL REFERENCES nursing_plans(id) ON DELETE CASCADE,
    action          VARCHAR NOT NULL,
    frequency_code  VARCHAR,
    frequency_name  VARCHAR,
    duration_days   INT,
    remark          TEXT,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISCONTINUED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- 护理任务表
CREATE TABLE nursing_tasks (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) REFERENCES nursing_service_periods(id),
    encounter_id    VARCHAR(32), -- REFERENCES encounters(id) (healthcare schema)
    plan_item_id    VARCHAR(32) REFERENCES nursing_plan_items(id),
    order_item_id   VARCHAR(32),
    task_type       VARCHAR NOT NULL CHECK (task_type IN (
                        'NURSING',
                        'REHABILITATION',
                        'LIVING_CARE',
                        'HEALTH_EDUCATION',
                        'OTHER'
                    )),
    description     VARCHAR NOT NULL,
    frequency_code  VARCHAR,
    frequency_name  VARCHAR,
    start_date      DATE,
    end_date        DATE,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_period ON nursing_tasks(period_id);

-- 护理任务执行记录表
CREATE TABLE nursing_task_executions (
    id                          VARCHAR(32) PRIMARY KEY,
    task_id                     VARCHAR(32) NOT NULL REFERENCES nursing_tasks(id),
    planned_time                TIMESTAMPTZ,
    actual_time                 TIMESTAMPTZ,
    executor                    VARCHAR,
    status                      VARCHAR DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','SKIPPED','CANCELLED')),
    stock_operation_detail_id   VARCHAR(32),
    quantity                    NUMERIC(15,4),
    note                        TEXT,
    metadata                    JSONB,
    created_at                  TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_exec_task ON nursing_task_executions(task_id);

-- 上门排班表
CREATE TABLE nursing_visit_schedules (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) NOT NULL REFERENCES nursing_service_periods(id),
    task_execution_id VARCHAR(32) REFERENCES nursing_task_executions(id),
    planned_date    DATE NOT NULL,
    planned_start   TIME,
    planned_end     TIME,
    caregiver       VARCHAR,
    actual_start    TIMESTAMPTZ,
    actual_end      TIMESTAMPTZ,
    travel_time     INT,
    status          VARCHAR DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_visit_period ON nursing_visit_schedules(period_id);
