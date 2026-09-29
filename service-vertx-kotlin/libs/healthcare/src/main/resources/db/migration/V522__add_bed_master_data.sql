-- =====================================================
-- Healthcare — 床位主数据（Beds）
-- 030 §4.5 W8：为养老入住表单提供 (department, ward) 候选来源。
-- 设计原则（030 D4）：
--   1. 只做候选来源，不做强制引用完整性：encounters.department/ward 仍是占用事实来源，
--      历史自由文本值不清洗、不阻断（029 的区间/床位冲突校验继续生效）
--   2. 业务键 (department, ward)：启用态至多一条，部分唯一索引兜底；
--      停用项不占用业务键（可保留历史停用行）
--   3. 状态中文枚举 启用/停用（CHECK 兜底 + 应用层白名单校验 400），默认 启用，
--      流转通过 PATCH /:id/status 独立进行
--   4. department/ward 长度 ≤50 字、trim 后非空由应用层保证（VARCHAR 不设长度上限）
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;

CREATE TABLE IF NOT EXISTS healthcare.beds (
    id          VARCHAR(32) PRIMARY KEY,                        -- ULID
    department  VARCHAR NOT NULL,                               -- 科室/病区/照护单元
    ward        VARCHAR NOT NULL,                               -- 病房/床位号
    label       VARCHAR,                                        -- 展示名称（可选）
    status      VARCHAR NOT NULL DEFAULT '启用' CHECK (status IN ('启用', '停用')),
    remark      VARCHAR,                                        -- 备注（≤500 字由应用层保证）
    metadata    JSONB,                                          -- 扩展元数据
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE healthcare.beds IS '床位主数据：养老入住表单的 (department, ward) 候选来源（不强制引用完整性）';
COMMENT ON COLUMN healthcare.beds.department IS '科室/病区/照护单元（trim 后非空、≤50 字由应用层保证）';
COMMENT ON COLUMN healthcare.beds.ward IS '病房/床位号（trim 后非空、≤50 字由应用层保证）';
COMMENT ON COLUMN healthcare.beds.label IS '展示名称（可选）';
COMMENT ON COLUMN healthcare.beds.status IS '状态中文枚举：启用/停用（应用层白名单管控，默认启用）';
COMMENT ON COLUMN healthcare.beds.remark IS '备注（≤500 字由应用层保证）';
COMMENT ON COLUMN healthcare.beds.metadata IS '扩展元数据';

-- 启用态业务键唯一：同一 (department, ward) 至多一条启用床位
CREATE UNIQUE INDEX IF NOT EXISTS uq_beds_enabled_identity
    ON healthcare.beds (department, ward) WHERE status = '启用';
