# 数据结构

> postgresql

```sql
-- =====================================================
-- 通用医疗/养老核心业务数据库结构
-- 涵盖患者管理、就诊、病历、诊断、医嘱
-- 兼容 HIS 与养老福利院系统
-- 设计原则：
--   - 表名统一使用复数
--   - 主键统一为 VARCHAR(32)，由应用层生成（UUID 等）
--   - 状态、类型字段采用 VARCHAR + CHECK 约束，避免枚举
--   - 无独立字典表（如科室、仓库、分类），直接存储可读标识
--   - 扩展信息统一使用 JSONB 的 metadata 字段
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare;

-- =====================================================
-- 一、患者与老人基础信息
-- =====================================================

-- 患者/老人表：记录所有医疗服务对象的基本信息
CREATE TABLE patients (
    id              VARCHAR(32) PRIMARY KEY,       -- 主键
    code            VARCHAR NOT NULL UNIQUE,       -- 患者编码/老人编号，业务唯一标识
    name            VARCHAR NOT NULL,              -- 姓名
    gender          VARCHAR CHECK (gender IN ('MALE','FEMALE','OTHER')), -- 性别
    birth_date      DATE,                          -- 出生日期
    identity_no     VARCHAR,                       -- 身份证号（医保/民政校验用）
    phone           VARCHAR,                       -- 联系电话
    type            VARCHAR NOT NULL CHECK (type IN ('INPATIENT','OUTPATIENT','ELDERLY','OTHER')), -- 人员类型：住院/门诊/养老长者/其他
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','DISCHARGED')), -- 状态：活跃/停用/出院或离院
    metadata        JSONB,                         -- 扩展信息（血型、婚姻、紧急联系人、照护等级等）
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- =====================================================
-- 二、就诊/住院/入住记录
-- =====================================================

-- 就诊/住院表：患者一次入院、门诊或养老入住记录
CREATE TABLE encounters (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_no    VARCHAR NOT NULL UNIQUE,       -- 就诊号/住院号/入住编号
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    type            VARCHAR NOT NULL CHECK (type IN ('OUTPATIENT','INPATIENT','ELDERLY_RESIDENT','DAY_CARE')), -- 就诊类型：门诊/住院/养老长期入住/日间照料
    ward            VARCHAR,                       -- 病区/楼层/护理站（如 '内一科'、'三楼'）
    room            VARCHAR,                       -- 房间/床位号
    attending_doctor VARCHAR,                      -- 主治医生/照护师
    admission_time  TIMESTAMPTZ,                   -- 入院/入住时间
    discharge_time  TIMESTAMPTZ,                   -- 出院/离开时间
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISCHARGED','CANCELLED')),
    metadata        JSONB,                         -- 扩展信息（转科记录、医保类型、费用等级等）
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- =====================================================
-- 三、病历与医疗文书
-- =====================================================

-- 病历记录表：支持分段式结构，覆盖门诊、住院、护理等各类文书
CREATE TABLE medical_records (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    section         VARCHAR NOT NULL CHECK (section IN (
                        'CHIEF_COMPLAINT',      -- 主诉
                        'PRESENT_ILLNESS',      -- 现病史
                        'PAST_HISTORY',         -- 既往史（可配合 patient_histories 表）
                        'PHYSICAL_EXAM',        -- 体格检查
                        'AUXILIARY_EXAM',       -- 辅助检查摘要
                        'DIAGNOSIS',            -- 初步诊断（文本描述，可由 diagnoses 表结构化补充）
                        'ADMISSION_NOTE',       -- 入院记录
                        'PROGRESS_NOTE',        -- 病程记录
                        'DISCHARGE_SUMMARY',    -- 出院小结
                        'NURSING_RECORD',       -- 护理记录（养老常用）
                        'OTHER'
                    )),
    content         TEXT,                         -- 文书内容（支持纯文本或 Markdown）
    author          VARCHAR,                      -- 记录人（医生/护士/护工）
    record_time     TIMESTAMPTZ DEFAULT now(),    -- 记录时间
    metadata        JSONB,                         -- 扩展（如关联模板ID、科室等）
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- =====================================================
-- 四、诊断信息
-- =====================================================

-- 诊断表：记录就诊过程中明确的诊断，通常关联 ICD 编码
CREATE TABLE diagnoses (
    id              VARCHAR(32) PRIMARY KEY,
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    diagnosis_type  VARCHAR NOT NULL CHECK (diagnosis_type IN ('PRINCIPAL','SECONDARY','ADMISSION','DISCHARGE')), -- 主要/次要/入院/出院诊断
    icd_code        VARCHAR,                       -- ICD-10 编码（如 'E11.9'）
    icd_name        VARCHAR,                       -- 诊断名称（如 '2型糖尿病'）
    diagnosed_by    VARCHAR,                       -- 诊断医生
    diagnosed_date  DATE,
    remark          TEXT,
    metadata        JSONB,                         -- 扩展（ICD 版本、伴随诊断等）
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- =====================================================
-- 五、病史信息（患者维度）
-- =====================================================

-- 患者病史表：管理患者级别的既往史、过敏史、家族史等，可跨就诊复用
CREATE TABLE patient_histories (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    category        VARCHAR NOT NULL CHECK (category IN (
                        'ALLERGY',              -- 过敏史（药物/食物）
                        'PAST_DISEASE',         -- 既往疾病
                        'SURGERY',              -- 手术史
                        'FAMILY_HISTORY',       -- 家族史
                        'SOCIAL_HISTORY',       -- 个人史（吸烟、饮酒、职业）
                        'OTHER'
                    )),
    content         TEXT,                         -- 描述（如 '青霉素过敏'、'胆囊切除术'）
    recorded_by     VARCHAR,                      -- 记录人
    recorded_date   DATE,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- =====================================================
-- 六、医嘱模块
-- =====================================================

-- 医嘱主表：包含长期/临时/备用医嘱及养老照护计划
CREATE TABLE orders (
    id              VARCHAR(32) PRIMARY KEY,
    order_no        VARCHAR NOT NULL UNIQUE,       -- 医嘱单号
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    order_type      VARCHAR NOT NULL CHECK (order_type IN ('LONG_TERM','TEMP','PRN','CARE_PLAN')), -- 长期/临时/备用/养老照护计划
    status          VARCHAR DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','REVIEWED','ACTIVE','STOPPED','CANCELLED')),
    prescriber      VARCHAR,                       -- 开嘱人（医生/护师）
    reviewer        VARCHAR,                       -- 审核人（药师/上级护师）
    start_time      TIMESTAMPTZ,                   -- 医嘱开始执行时间
    stop_time       TIMESTAMPTZ,                   -- 停止时间（长期医嘱或手动停止时填写）
    metadata        JSONB,                         -- 扩展（关联诊断、医保适应症、自备药标志等）
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- 医嘱明细表：包含具体项目、频次、剂量等，支持药品、治疗、护理、检查等类型
CREATE TABLE order_items (
    id              VARCHAR(32) PRIMARY KEY,
    order_id        VARCHAR(32) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    item_type       VARCHAR NOT NULL CHECK (item_type IN (
                        'DRUG',           -- 用药医嘱
                        'TREATMENT',      -- 治疗/处置（注射、换药、理疗等）
                        'NURSING',        -- 护理操作（翻身、监测等）
                        'DIET',           -- 饮食医嘱
                        'EXAMINATION',    -- 检查（影像、内镜）
                        'LABORATORY',     -- 检验（血、尿、培养）
                        'SURGERY',        -- 手术/操作
                        'OTHER'           -- 其他嘱托（健康教育、转科等）
                    )),
    material_id     VARCHAR(32) REFERENCES materials(id), -- 关联库存物资（仅药品/耗材时）
    dosage          NUMERIC(10,4),                         -- 每次剂量（以拆零单位计，如 1 片）
    dosage_unit     VARCHAR DEFAULT 'SPLIT' CHECK (dosage_unit IN ('PACKAGE','SPLIT')), -- 剂量单位类型
    frequency_code  VARCHAR,                               -- 频次编码（如 'QD','BID','TID','Q8H'）
    frequency_name  VARCHAR,                               -- 频次描述（如 '每日一次'）
    route           VARCHAR,                               -- 给药途径/用法（如 '口服'、'静脉滴注'）
    duration_days   INT,                                   -- 持续天数（用于计算结束时间）
    remark          TEXT,
    status          VARCHAR DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','STOPPED','CANCELLED')),
    metadata        JSONB,                                 -- 扩展（皮试标识、输液滴速、检查部位等）
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- 医嘱执行记录表：记录每次实际执行情况，药品类关联发药库存明细
CREATE TABLE order_executions (
    id                          VARCHAR(32) PRIMARY KEY,
    item_id                     VARCHAR(32) NOT NULL REFERENCES order_items(id),
    planned_time                TIMESTAMPTZ,                   -- 计划执行时间
    actual_time                 TIMESTAMPTZ,                   -- 实际执行时间
    executor                    VARCHAR,                       -- 执行人（护士/护工）
    status                      VARCHAR DEFAULT 'PENDING' CHECK (status IN ('PENDING','DONE','SKIPPED','CANCELLED')),
    stock_operation_detail_id   VARCHAR(32) REFERENCES stock_operation_details(id), -- 发药关联的出库明细ID
    quantity                    NUMERIC(15,4),                 -- 实际发药数量（包装单位，用于库存扣减）
    note                        TEXT,                           -- 备注（未执行原因等）
    created_at                  TIMESTAMPTZ DEFAULT now()
);

```
