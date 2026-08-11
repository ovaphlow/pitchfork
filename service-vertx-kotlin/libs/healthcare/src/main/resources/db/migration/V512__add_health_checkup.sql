-- =====================================================
-- Healthcare — 体检管理 (Health Checkup)
-- 机构年度体检常规：批次 + 参检名单快照 + 结果录入，
-- 异常结果可一键转体征（vital_sign_records）或转随访（followup_plans）。
-- 设计原则：
--   1. 批次按业务年唯一（同机构同一年仅一个批次，应用层预检 + 唯一索引兜底）
--   2. 批次状态机由应用层管控：草稿 → 进行中 → 已完成（单向，不可回退）
--   3. 名单为发布时快照（checkup_id + patient_id 唯一），MVP 允许追加补录并留痕
--   4. 结果异常由服务端按参考范围计算（数值项），文本项由录入人显式标记；
--      ref_min/ref_max 为录入时的显式参考范围，metadata.thresholds 可覆盖
--   5. 同一结果项至多转一次体征、至多转一次随访：vital_sign_id /
--      followup_plan_id 既作标记又作引用（重复转换返回 409）
--   6. 枚举中文值由应用层白名单管控，不加 CHECK 约束
-- =====================================================

SET search_path TO healthcare, public;

-- ----------------------------------------------------------------------------
-- 1. 体检批次表
--    一个机构一个业务年一个批次（checkup_year 唯一），确定参检范围与起止日期。
-- ----------------------------------------------------------------------------
CREATE TABLE health_checkups (
    id              VARCHAR(32) PRIMARY KEY,
    checkup_year    INT NOT NULL,                              -- 业务年（如 2026）
    name            VARCHAR NOT NULL,                          -- 批次名称（如 2026年度体检）
    status          VARCHAR NOT NULL DEFAULT '草稿',            -- 草稿 / 进行中 / 已完成
    start_date      DATE,                                      -- 体检开始日期
    end_date        DATE,                                      -- 体检结束日期
    operator        VARCHAR NOT NULL,                          -- 创建人（认证主体），客户端不得提交
    metadata        JSONB,                                     -- 扩展元数据
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE health_checkups IS '体检批次：机构年度体检常规（医疗/养老/儿保共用）';
COMMENT ON COLUMN health_checkups.checkup_year IS '业务年，同一机构同一业务年仅允许一个批次';
COMMENT ON COLUMN health_checkups.status IS '状态中文枚举：草稿/进行中/已完成（应用层单向流转）';
COMMENT ON COLUMN health_checkups.operator IS '创建人，来自认证主体，客户端不得提交';

-- 同一年度仅一个批次（应用层预检 409 + 唯一索引兜底防并发）
CREATE UNIQUE INDEX IF NOT EXISTS uq_health_checkups_year ON health_checkups(checkup_year);

-- ----------------------------------------------------------------------------
-- 2. 体检参检名单快照表
--    发布/补录时快照在册人员；encounter_id 为活动就诊/入住锚点（可空：
--    机构在册但无活动周期的患者无锚点，其异常项只能转体征或留在批次内）。
-- ----------------------------------------------------------------------------
CREATE TABLE health_checkup_members (
    id              VARCHAR(32) PRIMARY KEY,
    checkup_id      VARCHAR(32) NOT NULL REFERENCES health_checkups(id),
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    encounter_id    VARCHAR(32) REFERENCES encounters(id),     -- 活动周期锚点（可空）
    checked         BOOLEAN NOT NULL DEFAULT false,            -- 是否已录入体检结果
    checked_at      TIMESTAMPTZ,                               -- 首次录入结果时间
    operator        VARCHAR NOT NULL,                          -- 名单录入人（认证主体）
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE health_checkup_members IS '体检参检名单快照：批次内人员与活动周期锚点';
COMMENT ON COLUMN health_checkup_members.encounter_id IS '活动 ELDERLY_CARE/OUTPATIENT 周期锚点，可空';
COMMENT ON COLUMN health_checkup_members.checked IS '是否已录入体检结果（已检标记）';
COMMENT ON COLUMN health_checkup_members.operator IS '名单录入人，来自认证主体，客户端不得提交';

-- 同一批次内人员唯一（防重复快照/补录）
CREATE UNIQUE INDEX IF NOT EXISTS uq_health_checkup_members ON health_checkup_members(checkup_id, patient_id);

-- ----------------------------------------------------------------------------
-- 3. 体检结果表
--    数值项（value+unit+服务端异常判定）与文本项（text_value+人工异常标记）统一建模；
--    vital_sign_id / followup_plan_id 非空即表示已转出（幂等标记 + 引用）。
-- ----------------------------------------------------------------------------
CREATE TABLE health_checkup_results (
    id                  VARCHAR(32) PRIMARY KEY,
    checkup_id          VARCHAR(32) NOT NULL REFERENCES health_checkups(id),
    member_id           VARCHAR(32) NOT NULL REFERENCES health_checkup_members(id),
    patient_id          VARCHAR(32) NOT NULL REFERENCES patients(id),
    item_name           VARCHAR NOT NULL,                       -- 项目名（中文），如 收缩压/空腹血糖/心电图
    item_category       VARCHAR NOT NULL,                       -- 数值 / 文本
    value               NUMERIC,                                -- 数值项结果
    unit                VARCHAR,                                -- 数值项单位
    text_value          TEXT,                                   -- 文本项结论（心电图/胸透等）
    ref_min             NUMERIC,                                -- 显式参考下限（录入时可覆盖）
    ref_max             NUMERIC,                                -- 显式参考上限（录入时可覆盖）
    abnormal            BOOLEAN NOT NULL DEFAULT false,         -- 异常标记：数值项服务端计算，文本项录入人标记
    exam_date           DATE NOT NULL,                          -- 体检日期
    operator            VARCHAR NOT NULL,                       -- 录入人（认证主体），客户端不得提交
    vital_sign_id       VARCHAR(32),                            -- 转体征生成记录 ID（非空即已转出）
    followup_plan_id    VARCHAR(32),                            -- 转随访生成计划 ID（非空即已转出）
    metadata            JSONB,                                  -- 扩展字段，可含 thresholds 覆盖参考范围
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE health_checkup_results IS '体检结果：数值项自动判异常，文本项人工标记，可转体征/转随访';
COMMENT ON COLUMN health_checkup_results.item_category IS '项目类别中文枚举：数值/文本';
COMMENT ON COLUMN health_checkup_results.abnormal IS '异常标记：数值项由服务端按参考范围计算（含边界），文本项由录入人显式提交';
COMMENT ON COLUMN health_checkup_results.vital_sign_id IS '转体征生成的生命体征记录 ID；非空表示已转出（幂等）';
COMMENT ON COLUMN health_checkup_results.followup_plan_id IS '转随访生成的随访计划 ID；非空表示已转出（幂等）';
COMMENT ON COLUMN health_checkup_results.metadata IS '扩展元数据；可含 thresholds 覆盖参考范围 {"thresholds":{"SYSTOLIC_BP":{"min":..,"max":..}}}';

CREATE INDEX IF NOT EXISTS idx_health_checkup_results_checkup ON health_checkup_results(checkup_id);
CREATE INDEX IF NOT EXISTS idx_health_checkup_results_patient ON health_checkup_results(patient_id);
CREATE INDEX IF NOT EXISTS idx_health_checkup_results_abnormal ON health_checkup_results(checkup_id, abnormal);
CREATE INDEX IF NOT EXISTS idx_health_checkup_members_checkup ON health_checkup_members(checkup_id, checked);
