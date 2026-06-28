-- ============================================================================
-- 医疗健康通用模块 - 核心表结构 (可适配 HIS / 养老 / 儿保)
-- Schema: healthcare
-- 设计原则:
--   1. 所有类型字段不加 CHECK 约束，由应用层或字典表管控，实现多场景适配
--   2. 医嘱主表极简化，差异化业务细节统一压入 order_details JSONB 字段
--   3. 以 encounter (就诊/入住周期) 为业务核心串联全部数据
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

-- ----------------------------------------------------------------------------
-- 1. 病人/老人/服务对象主表
--    兼容 HIS 的患者、养老系统的老人、儿保的儿童
-- ----------------------------------------------------------------------------
CREATE TABLE patients (
    id              VARCHAR(32) PRIMARY KEY,                     -- 全局唯一ID
    name            VARCHAR NOT NULL,                       -- 姓名
    gender          VARCHAR NOT NULL DEFAULT '',                 -- 性别，不硬编码 CHECK
    birth_date      DATE,                                       -- 出生日期
    id_card_no      VARCHAR UNIQUE,                         -- 身份证号
    phone           VARCHAR,                                -- 联系电话
    address         TEXT,                                       -- 地址
    emergency_contact JSONB DEFAULT '{}',                       -- 紧急联系人 {"name":"...","phone":"..."}
    medical_insurance VARCHAR DEFAULT '',                       -- 医保号 / 保险类型
    allergies       JSONB DEFAULT '[]',                         -- 过敏史 [{"allergen":"青霉素","reaction":"皮疹"}]
    past_history    TEXT,                                       -- 既往病史摘要
    metadata        JSONB,                                      -- 扩展元数据 (任何场景的额外信息)
    status          VARCHAR DEFAULT 'ACTIVE',                   -- 状态 (ACTIVE / INACTIVE / DECEASED)
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE patients IS '服务对象主表 (患者/老人/儿童)';
COMMENT ON COLUMN patients.id IS '全局唯一ID';
COMMENT ON COLUMN patients.gender IS '性别，推荐使用 ''男''/''女'' 或编码';
COMMENT ON COLUMN patients.allergies IS '过敏史 JSON 数组';
COMMENT ON COLUMN patients.metadata IS '扩展元数据，可存放特定系统需要的额外信息';
COMMENT ON COLUMN patients.status IS '生命或账户状态';

CREATE INDEX IF NOT EXISTS idx_patients_name ON patients(name);
CREATE INDEX IF NOT EXISTS idx_patients_phone ON patients(phone);
CREATE INDEX IF NOT EXISTS idx_patients_id_card ON patients(id_card_no);

-- ----------------------------------------------------------------------------
-- 2. 就诊/住院/入住周期表
--    一次 encounter 代表服务对象与机构的一次持续接触
-- ----------------------------------------------------------------------------
CREATE TABLE encounters (
    id              VARCHAR(32) PRIMARY KEY,                     -- 全局唯一ID
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    encounter_type  VARCHAR NOT NULL DEFAULT '',             -- 类型: INPATIENT/OUTPATIENT/ELDERLY_CARE/CHILD_HEALTH 等
    department      VARCHAR,                                -- 科室/病区/照护单元
    ward            VARCHAR,                                -- 病房/床位号
    admit_date      TIMESTAMPTZ,                                -- 开始时间 (入院/就诊/入住)
    discharge_date  TIMESTAMPTZ,                                -- 结束时间 (出院/离院)
    admitting_diagnosis TEXT,                                   -- 入科诊断/初步印象
    discharge_diagnosis TEXT,                                   -- 出院诊断/阶段总结
    attending_physician VARCHAR,                                -- 主治医生/责任保健师
    status          VARCHAR(20) DEFAULT 'ACTIVE',               -- ACTIVE / DISCHARGED / TRANSFERRED
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE encounters IS '就诊/住院/入住周期表，一个 encounter 对应一次持续的服务接触';
COMMENT ON COLUMN encounters.encounter_type IS '类型，由各系统字典决定，不强制 CHECK';
COMMENT ON COLUMN encounters.status IS '当前周期状态';

CREATE INDEX IF NOT EXISTS idx_encounters_patient ON encounters(patient_id);
CREATE INDEX IF NOT EXISTS idx_encounters_status ON encounters(status);
CREATE INDEX IF NOT EXISTS idx_encounters_admit ON encounters(admit_date);

-- ----------------------------------------------------------------------------
-- 3. 诊断/评估记录表
--    兼容疾病诊断、儿保发育评估、养老功能评估等
-- ----------------------------------------------------------------------------
CREATE TABLE diagnoses (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    diagnosis_type  VARCHAR NOT NULL DEFAULT '',             -- 诊断类型: ADMITTING/PRIMARY/ASSESSMENT/SCREENING 等
    icd_code        VARCHAR,                                -- ICD 编码
    diagnosis_text  TEXT NOT NULL,                              -- 诊断或评估描述
    diagnosis_date  DATE NOT NULL,
    physician       VARCHAR,                                    -- 诊断医生 / 评估人
    is_major        BOOLEAN DEFAULT FALSE,                      -- 是否为主要诊断/核心评估结论
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE diagnoses IS '诊断/评估记录，涵盖疾病、发育筛查、功能评估等';
COMMENT ON COLUMN diagnoses.diagnosis_type IS '诊断类型，由系统字典定义，如 PRIMARY/COMORBIDITY/WELL_CHILD';

CREATE INDEX IF NOT EXISTS idx_diagnoses_encounter ON diagnoses(encounter_id);
CREATE INDEX IF NOT EXISTS idx_diagnoses_icd ON diagnoses(icd_code);

-- ----------------------------------------------------------------------------
-- 4. 医嘱表 (极简主表 + 参数化详情)
--    所有类型的医嘱都只有一份结构，业务差异全部进入 order_details JSONB
-- ----------------------------------------------------------------------------
CREATE TABLE medical_orders (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    order_type      VARCHAR NOT NULL DEFAULT '',             -- 医嘱大类: MEDICATION/NURSING/DIET/VACCINE/THERAPY 等
    order_content   TEXT,                                       -- 医嘱显示文本 / 备注
    order_details   JSONB DEFAULT '{}',                         -- 结构化参数 (剂量、频次、穴位、疫苗编码等)
    start_time      TIMESTAMPTZ,
    end_time        TIMESTAMPTZ,
    doctor          VARCHAR NOT NULL,                           -- 开嘱医生 / 保健师
    nurse           VARCHAR,                                    -- 执行护士 / 护理员
    status          VARCHAR DEFAULT 'ACTIVE',               -- ACTIVE/DISCONTINUED/COMPLETED/CANCELLED
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE medical_orders IS '医嘱主表，公共流程字段在此，具体业务细节在 order_details JSONB';
COMMENT ON COLUMN medical_orders.order_type IS '医嘱类型，如 MEDICATION/NURSING/EXAMINATION/LAB_TEST/VACCINE';
COMMENT ON COLUMN medical_orders.order_content IS '用于展示的医嘱全文，也可作为自由文本输入';
COMMENT ON COLUMN medical_orders.order_details IS '核心：结构化参数，例 {"drug_code":"X","dose":1.2,"unit":"g","route":"IV","frequency":"bid"}';

CREATE INDEX IF NOT EXISTS idx_orders_encounter ON medical_orders(encounter_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON medical_orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_type ON medical_orders(order_type);

-- ----------------------------------------------------------------------------
-- 5. 病程/照护记录表
--    通用记录表，承载各种类型的日常记录
-- ----------------------------------------------------------------------------
CREATE TABLE progress_notes (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    note_type       VARCHAR NOT NULL DEFAULT '',             -- 记录类型: DAILY/SHIFT/NURSING/REHAB/GROWTH 等
    content         TEXT NOT NULL,
    physician       VARCHAR NOT NULL,                           -- 记录人
    record_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE progress_notes IS '病程/日常照护/成长记录通用表';
COMMENT ON COLUMN progress_notes.note_type IS '记录类型，如 DAILY(日常病程)、NURSING(护理记录)、GROWTH(儿保生长记录)';

CREATE INDEX IF NOT EXISTS idx_progress_encounter ON progress_notes(encounter_id);
CREATE INDEX IF NOT EXISTS idx_progress_time ON progress_notes(record_time);
CREATE INDEX IF NOT EXISTS idx_progress_type ON progress_notes(note_type);

-- ----------------------------------------------------------------------------
-- 6. 病历档案 / 文书表
--    存储入院记录、出院小结、疫苗接种知情书等正式文档
-- ----------------------------------------------------------------------------
CREATE TABLE medical_records (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    record_type     VARCHAR NOT NULL DEFAULT '',             -- 文书类型: ADMISSION/DISCHARGE/CARE_PLAN/VACCINATION_CONSENT 等
    title           VARCHAR NOT NULL,
    content         TEXT,                                       -- 纯文本或富文本
    content_blocks  JSONB DEFAULT '[]',                         -- 结构化内容 (可用于 PDF 渲染)
    physician       VARCHAR NOT NULL,                           -- 责任医生/记录人
    record_date     DATE NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE medical_records IS '病历/档案/文书表，存放各种正式医疗文档';
COMMENT ON COLUMN medical_records.record_type IS '文书类型，如 ADMISSION(入院记录)、DISCHARGE_SUMMARY(出院小结)';

CREATE INDEX IF NOT EXISTS idx_records_encounter ON medical_records(encounter_id);
CREATE INDEX IF NOT EXISTS idx_records_type ON medical_records(record_type);
