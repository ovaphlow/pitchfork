# 数据结构

> postgresql

```sql
-- =====================================================
-- 护理模块 - 独立数据结构
-- 涵盖机构护理与家庭护理
-- 表名复数，主键 VARCHAR(32)，状态 VARCHAR + CHECK
-- =====================================================

CREATE SCHEMA IF NOT EXISTS nursing;
SET search_path TO nursing;

-- 护理服务周期表：家庭护理等场景的核心聚合根，机构内护理可继续使用 encounters
CREATE TABLE nursing_service_periods (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id), -- 直接关联患者
    service_type    VARCHAR NOT NULL CHECK (service_type IN (
                        'HOME_CARE',          -- 居家护理
                        'COMMUNITY_CARE',     -- 社区护理
                        'HOSPICE'             -- 安宁疗护
                    )),
    start_date      DATE NOT NULL,
    end_date        DATE,
    coordinator     VARCHAR,                  -- 责任护士/个案管理师
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','COMPLETED','CANCELLED')),
    metadata        JSONB,                    -- 支付来源、服务频次约定、照护等级等
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- 护理评估表：支持关联就诊（机构内）或护理服务周期（家庭护理），二选一
CREATE TABLE nursing_assessments (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) REFERENCES encounters(id),          -- 关联就诊（机构内护理）
    period_id       VARCHAR(32) REFERENCES nursing_service_periods(id), -- 关联服务周期（家庭护理）
    assess_type     VARCHAR NOT NULL CHECK (assess_type IN (
                        'ADMISSION',          -- 入院评估
                        'FALL_RISK',          -- 跌倒风险评估
                        'PRESSURE_SORE',      -- 压疮风险评估
                        'PAIN',               -- 疼痛评估
                        'BARTHEL',            -- 生活自理能力评估
                        'NUTRITION',          -- 营养评估
                        'HOME_ENVIRONMENT',   -- 居家环境安全评估（家庭护理特有）
                        'OTHER'
                    )),
    assess_date     DATE NOT NULL,
    assessor        VARCHAR,                  -- 评估人
    total_score     NUMERIC(5,1),             -- 总分
    result_level    VARCHAR,                  -- 评估等级
    detail          JSONB,                    -- 评估明细（各条目得分）
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    -- 确保至少关联一边
    CONSTRAINT chk_assess_ref CHECK (encounter_id IS NOT NULL OR period_id IS NOT NULL)
);

CREATE INDEX idx_assess_encounter ON nursing_assessments(encounter_id);
CREATE INDEX idx_assess_period ON nursing_assessments(period_id);

-- 护理计划表：关联服务周期或就诊，本次设计以服务周期为主
CREATE TABLE nursing_plans (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) NOT NULL REFERENCES nursing_service_periods(id), -- 必填，表示计划归属哪个服务周期
    encounter_id    VARCHAR(32) REFERENCES encounters(id),     -- 可选，若需追溯到某次住院
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

CREATE INDEX idx_plan_period ON nursing_plans(period_id);

-- 护理计划措施明细表
CREATE TABLE nursing_plan_items (
    id              VARCHAR(32) PRIMARY KEY,
    plan_id         VARCHAR(32) NOT NULL REFERENCES nursing_plans(id) ON DELETE CASCADE,
    action          VARCHAR NOT NULL,          -- 措施描述
    frequency_code  VARCHAR,                   -- 频次编码
    frequency_name  VARCHAR,
    duration_days   INT,
    remark          TEXT,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISCONTINUED')),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- 护理任务表：具体执行的护理操作，可来源于计划措施，也可临时添加
CREATE TABLE nursing_tasks (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) REFERENCES nursing_service_periods(id), -- 归属服务周期
    encounter_id    VARCHAR(32) REFERENCES encounters(id),              -- 可选关联就诊
    plan_item_id    VARCHAR(32) REFERENCES nursing_plan_items(id),      -- 来源计划措施
    order_item_id   VARCHAR(32),                                       -- 若与医嘱联动时的医嘱明细ID（外部模块，不做外键）
    task_type       VARCHAR NOT NULL CHECK (task_type IN (
                        'NURSING',            -- 护理操作
                        'REHABILITATION',     -- 康复训练
                        'LIVING_CARE',        -- 生活照料
                        'HEALTH_EDUCATION',   -- 健康教育
                        'OTHER'
                    )),
    description     VARCHAR NOT NULL,         -- 任务描述
    frequency_code  VARCHAR,
    frequency_name  VARCHAR,
    start_date      DATE,
    end_date        DATE,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
    metadata        JSONB,                    -- 关联耗材清单、执行说明等
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_task_period ON nursing_tasks(period_id);

-- 护理任务执行记录表
CREATE TABLE nursing_task_executions (
    id                          VARCHAR(32) PRIMARY KEY,
    task_id                     VARCHAR(32) NOT NULL REFERENCES nursing_tasks(id),
    planned_time                TIMESTAMPTZ,          -- 计划执行时间
    actual_time                 TIMESTAMPTZ,          -- 实际执行时间
    executor                    VARCHAR,              -- 执行人
    status                      VARCHAR DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','SKIPPED','CANCELLED')),
    stock_operation_detail_id   VARCHAR(32),          -- 关联库存出库明细（外部模块，不做外键）
    quantity                    NUMERIC(15,4),        -- 耗材使用数量（包装单位）
    note                        TEXT,
    metadata                    JSONB,                -- 签到定位、操作时长等
    created_at                  TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_exec_task ON nursing_task_executions(task_id);

-- 上门排班表：用于家庭护理的上门服务安排
CREATE TABLE nursing_visit_schedules (
    id              VARCHAR(32) PRIMARY KEY,
    period_id       VARCHAR(32) NOT NULL REFERENCES nursing_service_periods(id),
    task_execution_id VARCHAR(32) REFERENCES nursing_task_executions(id), -- 可关联具体执行记录
    planned_date    DATE NOT NULL,
    planned_start   TIME,
    planned_end     TIME,
    caregiver       VARCHAR,                    -- 指派护理员
    actual_start    TIMESTAMPTZ,
    actual_end      TIMESTAMPTZ,
    travel_time     INT,                        -- 路途耗时（分钟）
    status          VARCHAR DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
    metadata        JSONB,                      -- 交通工具、签到信息等
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_visit_period ON nursing_visit_schedules(period_id);

```
