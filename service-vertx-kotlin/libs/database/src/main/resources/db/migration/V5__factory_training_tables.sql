-- ============================================================
-- 工厂培训与知识库系统 - 数据库建表脚本 (PostgreSQL)
-- Flyway Migration V5
-- 注意: users 表已在 V1 基线中存在，不再重复创建。
--       员工通过 employees 表关联 users。
-- ============================================================

-- 启用 pgcrypto 扩展（已存在则跳过）
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 定义生成32位UUID的辅助函数
CREATE OR REPLACE FUNCTION generate_uid() RETURNS VARCHAR(32) AS $$
BEGIN
    RETURN replace(gen_random_uuid()::text, '-', '');
END;
$$ LANGUAGE plpgsql;

-- ======================= 基础员工表 =======================

-- 部门表
CREATE TABLE IF NOT EXISTS departments (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    name            VARCHAR(100) NOT NULL,
    parent_id       VARCHAR(32) REFERENCES departments(id),
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 员工表（关联已有 users 表）
CREATE TABLE IF NOT EXISTS employees (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    user_id         VARCHAR(32),
    employee_no     VARCHAR(50) NOT NULL UNIQUE,
    name            VARCHAR(50) NOT NULL,
    department_id   VARCHAR(32) REFERENCES departments(id),
    position_id     VARCHAR(32),
    hire_date       DATE,
    is_on_job       BOOLEAN NOT NULL DEFAULT TRUE,
    extra           JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ======================= 知识库相关 =======================

CREATE TABLE IF NOT EXISTS knowledge_entries (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    title               VARCHAR(500) NOT NULL,
    type                VARCHAR(50) NOT NULL CHECK (type IN ('SOP', 'OPL', '故障案例', '安全须知', '工艺参数表', '设备手册')),
    status              VARCHAR(20) NOT NULL DEFAULT '草稿' CHECK (status IN ('草稿', '已发布', '已归档')),
    current_version_id  VARCHAR(32),
    category_ids        VARCHAR(32)[] DEFAULT '{}',
    device_ids          VARCHAR(32)[] DEFAULT '{}',
    position_ids        VARCHAR(32)[] DEFAULT '{}',
    tags                VARCHAR(100)[] DEFAULT '{}',
    extra               JSONB DEFAULT '{}',
    created_by          VARCHAR(32),
    updated_by          VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS knowledge_versions (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    entry_id        VARCHAR(32) NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE,
    version_number  VARCHAR(20) NOT NULL,
    content         TEXT,
    content_blocks  JSONB DEFAULT '[]',
    attachment_files JSONB DEFAULT '[]',
    change_note     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT '草稿' CHECK (status IN ('草稿', '审批中', '已生效', '已归档')),
    approved_by     VARCHAR(32),
    approved_at     TIMESTAMPTZ,
    created_by      VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_current_version') THEN
        ALTER TABLE knowledge_entries
            ADD CONSTRAINT fk_current_version
            FOREIGN KEY (current_version_id) REFERENCES knowledge_versions(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS knowledge_categories (
    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    name        VARCHAR(100) NOT NULL,
    parent_id   VARCHAR(32) REFERENCES knowledge_categories(id),
    sort_order  SMALLINT NOT NULL DEFAULT 0,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS knowledge_feedbacks (
    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    entry_id    VARCHAR(32) NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('提问', '纠错', '建议')),
    content     TEXT NOT NULL,
    reply       JSONB DEFAULT '[]',
    status      VARCHAR(20) NOT NULL DEFAULT '待处理' CHECK (status IN ('待处理', '已回复', '已关闭')),
    created_by  VARCHAR(32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ======================= 岗位与技能相关 =======================

CREATE TABLE IF NOT EXISTS positions (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    name                VARCHAR(100) NOT NULL,
    parent_id           VARCHAR(32) REFERENCES positions(id),
    skill_requirements  JSONB DEFAULT '[]',
    assessment_config   JSONB DEFAULT '{}',
    extra               JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS skills (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    name                VARCHAR(100) NOT NULL,
    category            VARCHAR(50) CHECK (category IN ('操作', '安全', '维保', '其它')),
    evaluation_criteria JSONB DEFAULT '{}',
    default_validity    JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS employee_skills (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    employee_id         VARCHAR(32) NOT NULL,
    skill_id            VARCHAR(32) NOT NULL REFERENCES skills(id),
    current_level       SMALLINT CHECK (current_level BETWEEN 1 AND 4),
    assessed_date       DATE,
    assessor_id         VARCHAR(32),
    expire_date         DATE,
    assessment_record   JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Employee skills unique constraint (if not exists)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'employee_skills_employee_id_skill_id_key') THEN
        ALTER TABLE employee_skills ADD UNIQUE (employee_id, skill_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS certificates (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    name            VARCHAR(200) NOT NULL,
    validity_config JSONB DEFAULT '{}',
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS employee_certificates (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    employee_id     VARCHAR(32) NOT NULL,
    certificate_id  VARCHAR(32) NOT NULL REFERENCES certificates(id),
    issue_date      DATE,
    expire_date     DATE,
    attachment      VARCHAR(500),
    extra           JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'employee_certificates_employee_id_certificate_id_key') THEN
        ALTER TABLE employee_certificates ADD UNIQUE (employee_id, certificate_id);
    END IF;
END $$;

-- ======================= 培训与考试相关 =======================

CREATE TABLE IF NOT EXISTS courses (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    title               VARCHAR(200) NOT NULL,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('线上', '线下实操')),
    cover_image         VARCHAR(500),
    target_positions    VARCHAR(32)[] DEFAULT '{}',
    completion_rules    JSONB DEFAULT '{}',
    status              VARCHAR(20) NOT NULL DEFAULT '启用' CHECK (status IN ('启用', '停用')),
    created_by          VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS course_chapters (
    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    course_id   VARCHAR(32) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    sort_order  SMALLINT NOT NULL DEFAULT 0,
    title       VARCHAR(200) NOT NULL,
    blocks      JSONB DEFAULT '[]',
    quiz_config JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS questions (
    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    type        VARCHAR(20) NOT NULL CHECK (type IN ('单选', '多选', '判断', '填空', '看图识错')),
    difficulty  SMALLINT CHECK (difficulty BETWEEN 1 AND 5),
    tags        VARCHAR(100)[] DEFAULT '{}',
    content     JSONB NOT NULL DEFAULT '{}',
    options     JSONB DEFAULT '[]',
    answer      JSONB NOT NULL,
    explanation TEXT,
    created_by  VARCHAR(32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS exam_papers (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    title               VARCHAR(200) NOT NULL,
    duration_minutes    SMALLINT NOT NULL,
    pass_score          NUMERIC(5,2) NOT NULL,
    generation_strategy JSONB NOT NULL DEFAULT '{}',
    anti_cheat_config   JSONB DEFAULT '{}',
    extra_rules         JSONB DEFAULT '{}',
    created_by          VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS training_assignments (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    course_id       VARCHAR(32) NOT NULL REFERENCES courses(id),
    assign_type     VARCHAR(20) NOT NULL CHECK (assign_type IN ('手动指派', '自动触发')),
    trigger_rule    JSONB DEFAULT '{}',
    deadline        TIMESTAMPTZ,
    target_type     VARCHAR(20) NOT NULL CHECK (target_type IN ('用户', '岗位', '部门')),
    target_ids      VARCHAR(32)[] NOT NULL DEFAULT '{}',
    created_by      VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS learning_progress (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    assignment_id   VARCHAR(32) NOT NULL REFERENCES training_assignments(id) ON DELETE CASCADE,
    employee_id     VARCHAR(32) NOT NULL,
    chapter_id      VARCHAR(32) REFERENCES course_chapters(id),
    progress_percent NUMERIC(5,2) DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT '学习中' CHECK (status IN ('学习中', '已完成')),
    detail          JSONB DEFAULT '{}',
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'learning_progress_assignment_id_employee_id_chapter_id_key') THEN
        ALTER TABLE learning_progress ADD UNIQUE (assignment_id, employee_id, chapter_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS exam_records (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    employee_id         VARCHAR(32) NOT NULL,
    paper_id            VARCHAR(32) NOT NULL REFERENCES exam_papers(id),
    start_time          TIMESTAMPTZ NOT NULL,
    end_time            TIMESTAMPTZ,
    score               NUMERIC(5,2),
    passed              BOOLEAN,
    answers_snapshot    JSONB DEFAULT '[]',
    cheat_flags         JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ======================= AI智能助手相关 =======================

CREATE TABLE IF NOT EXISTS ai_qa_logs (
    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    user_id         VARCHAR(32),
    question        TEXT NOT NULL,
    answer          TEXT,
    sources         JSONB DEFAULT '[]',
    feedback        VARCHAR(20) CHECK (feedback IN ('有用', '没用')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS preventive_push_rules (
    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    name                VARCHAR(200) NOT NULL,
    trigger_metric      VARCHAR(100) NOT NULL,
    threshold           NUMERIC(10,2),
    target_positions    VARCHAR(32)[] DEFAULT '{}',
    target_course_id    VARCHAR(32) REFERENCES courses(id),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    extra               JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS faq_pairs (
    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    question    TEXT NOT NULL,
    answer      TEXT NOT NULL,
    tags        VARCHAR(100)[] DEFAULT '{}',
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  VARCHAR(32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ======================= 现场支持相关 =======================

CREATE TABLE IF NOT EXISTS device_qr_codes (
    id                      VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    device_id               VARCHAR(32) NOT NULL,
    code                    VARCHAR(100) NOT NULL UNIQUE,
    linked_knowledge_ids    VARCHAR(32)[] DEFAULT '{}',
    offline_cache_config    JSONB DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS offline_cache_policies (
    id                      VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),
    position_id             VARCHAR(32) REFERENCES positions(id),
    cache_size_limit_mb     SMALLINT DEFAULT 100,
    include_knowledge_types VARCHAR(50)[] DEFAULT '{}',
    include_recent_days     SMALLINT DEFAULT 30,
    extra                   JSONB DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ======================= 索引创建 =======================

CREATE INDEX IF NOT EXISTS idx_knowledge_entries_type_status ON knowledge_entries(type, status);
CREATE INDEX IF NOT EXISTS idx_knowledge_entries_tags ON knowledge_entries USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_knowledge_entries_extra ON knowledge_entries USING GIN(extra);
CREATE INDEX IF NOT EXISTS idx_knowledge_versions_entry_id ON knowledge_versions(entry_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_feedbacks_entry_id ON knowledge_feedbacks(entry_id);

CREATE INDEX IF NOT EXISTS idx_employee_skills_employee ON employee_skills(employee_id);
CREATE INDEX IF NOT EXISTS idx_employee_skills_skill ON employee_skills(skill_id);
CREATE INDEX IF NOT EXISTS idx_employee_certificates_employee ON employee_certificates(employee_id);

CREATE INDEX IF NOT EXISTS idx_course_chapters_course ON course_chapters(course_id);

CREATE INDEX IF NOT EXISTS idx_questions_tags ON questions USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_questions_content ON questions USING GIN(content);

CREATE INDEX IF NOT EXISTS idx_training_assignments_course ON training_assignments(course_id);
CREATE INDEX IF NOT EXISTS idx_training_assignments_target ON training_assignments(target_type, target_ids);

CREATE INDEX IF NOT EXISTS idx_learning_progress_assignment ON learning_progress(assignment_id);
CREATE INDEX IF NOT EXISTS idx_learning_progress_employee ON learning_progress(employee_id);

CREATE INDEX IF NOT EXISTS idx_exam_records_employee ON exam_records(employee_id);
CREATE INDEX IF NOT EXISTS idx_exam_records_paper ON exam_records(paper_id);

CREATE INDEX IF NOT EXISTS idx_device_qr_codes_code ON device_qr_codes(code);
CREATE INDEX IF NOT EXISTS idx_device_qr_codes_device ON device_qr_codes(device_id);

CREATE INDEX IF NOT EXISTS idx_ai_qa_logs_user ON ai_qa_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_qa_logs_created ON ai_qa_logs(created_at);

-- ======================= 注释 =======================

COMMENT ON TABLE knowledge_entries IS '知识条目主表';
COMMENT ON COLUMN knowledge_entries.extra IS '扩展属性，如故障代码、参数列表等';
COMMENT ON TABLE knowledge_versions IS '知识版本表';
COMMENT ON COLUMN knowledge_versions.content_blocks IS '结构化内容块，支持图文/视频/音频/步骤';
COMMENT ON TABLE employee_skills IS '员工技能档案';
COMMENT ON COLUMN employee_skills.assessment_record IS '最近考核详情，含检查表、照片、评语';
COMMENT ON TABLE exam_papers IS '试卷表';
COMMENT ON COLUMN exam_papers.generation_strategy IS '组卷策略JSON';
COMMENT ON TABLE exam_records IS '考试记录';
COMMENT ON COLUMN exam_records.answers_snapshot IS '交卷时的题目快照与用户答案';
