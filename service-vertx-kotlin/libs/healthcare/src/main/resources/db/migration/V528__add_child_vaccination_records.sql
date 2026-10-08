-- =====================================================
-- Healthcare — 儿保预防接种记录（V528，052 P3）
--
-- 背景：035 只在 `child_health_profiles.vaccination_records` JSONB 里存接种摘要，
-- 无规范化记录、无排程、无状态判定、无逾期提醒。052 D4 决定新增规范化表，
-- 一次性从 035 的 JSONB 回填；排程为国家免疫规划疫苗常量（应用层），
-- 状态（待接种/临期/逾期/暂缓/拒种）**读时计算**，不做后台定时任务。
--
-- 口径：
--   1. 一行一剂次：`(patient_id, vaccine_code, dose_no)` 唯一（幂等补录/回填的兜底）。
--   2. `status` 只描述**已发生的记录**：已接种 / 暂缓 / 拒种（应用层白名单）；
--      「待接种/临期/逾期」是排程与记录比对后的**读时结论**，不落库。
--   3. 035 的 `vaccination_records` JSONB 保留为回填源与只读遗留，不删改。
--   4. `operator` 一律取认证主体；回填行 operator 记为 `system:backfill-035`。
--   5. 不重跑 jOOQ codegen：新表一律以 DSL.table/DSL.field 按名引用。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE child_vaccination_records (
    id              VARCHAR(32) PRIMARY KEY,
    patient_id      VARCHAR(32) NOT NULL REFERENCES patients(id),
    vaccine_code    VARCHAR NOT NULL,                       -- 国家免疫规划疫苗编码（应用层常量表）
    vaccine_name    VARCHAR NOT NULL,                       -- 疫苗中文名（冗余快照，便于展示与回填）
    dose_no         INTEGER NOT NULL DEFAULT 1,             -- 剂次序号（从 1 起）
    administered_at DATE,                                   -- 接种日期（暂缓/拒种时可为空）
    facility        VARCHAR,                                -- 接种机构
    batch_no        VARCHAR,                                -- 疫苗批号
    status          VARCHAR NOT NULL DEFAULT '已接种',       -- 已接种 / 暂缓 / 拒种
    operator        VARCHAR NOT NULL,                       -- 记录人（认证主体）
    note            TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE child_vaccination_records IS '儿保预防接种记录：一行一剂次，052 P3；排程与「待接种/临期/逾期」为读时计算';
COMMENT ON COLUMN child_vaccination_records.vaccine_code IS '国家免疫规划疫苗编码，与前端 lib/vaccination.ts 的 VACCINATION_SCHEDULE 同源';
COMMENT ON COLUMN child_vaccination_records.status IS '记录状态中文枚举：已接种/暂缓/拒种（应用层白名单）；待接种/临期/逾期是读时结论，不落库';
COMMENT ON COLUMN child_vaccination_records.operator IS '记录人，来自认证主体，客户端不得提交；035 回填行为 system:backfill-035';

CREATE UNIQUE INDEX IF NOT EXISTS uq_child_vaccination_records
    ON child_vaccination_records(patient_id, vaccine_code, dose_no);
CREATE INDEX IF NOT EXISTS idx_child_vaccination_records_patient
    ON child_vaccination_records(patient_id, administered_at DESC);

-- 一次性回填：把 035 的结构化摘要 [{vaccine,dose,date,facility}] 展开为规范化剂次行。
-- 只回填能解析出「疫苗名 + 剂次」的条目；疫苗名按中文名/常见别名匹配免疫规划目录，
-- 匹配不到时以疫苗名本身作为 code（保留原始事实，不丢数据），由护士后续人工校正。
-- 幂等：ON CONFLICT 命中唯一索引则跳过（重复执行本迁移不会产生重复行）。
INSERT INTO child_vaccination_records
    (id, patient_id, vaccine_code, vaccine_name, dose_no, administered_at, facility, status, operator, metadata)
SELECT
    upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 26)),
    p.patient_id,
    COALESCE(s.vaccine_code, trim(entry->>'vaccine')),
    trim(entry->>'vaccine'),
    COALESCE(NULLIF(regexp_replace(COALESCE(entry->>'dose', ''), '[^0-9]', '', 'g'), '')::int, 1),
    NULLIF(
        CASE
            WHEN trim(COALESCE(entry->>'date', '')) ~ '^\d{4}-\d{2}-\d{2}$'
            THEN trim(entry->>'date')
        END, '')::date,
    NULLIF(trim(COALESCE(entry->>'facility', '')), ''),
    '已接种',
    'system:backfill-035',
    jsonb_build_object('source', '035-vaccination-records-jsonb')
FROM healthcare.child_health_profiles p
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p.vaccination_records, '[]'::jsonb)) AS entry
LEFT JOIN (
    VALUES
        ('卡介苗', 'BCG'),
        ('乙肝疫苗', 'HEPB'),
        ('乙型肝炎疫苗', 'HEPB'),
        ('脊灰疫苗', 'POLIO'),
        ('脊髓灰质炎疫苗', 'POLIO'),
        ('百白破疫苗', 'DTAP'),
        ('麻腮风疫苗', 'MMR'),
        ('麻疹疫苗', 'MMR'),
        ('乙脑疫苗', 'JE'),
        ('流脑疫苗', 'MENINGOCOCCAL_A'),
        ('甲肝疫苗', 'HEPA')
) AS s(vaccine_name, vaccine_code) ON s.vaccine_name = trim(entry->>'vaccine')
WHERE jsonb_typeof(entry) = 'object'
  AND COALESCE(trim(entry->>'vaccine'), '') <> ''
ON CONFLICT (patient_id, vaccine_code, dose_no) DO NOTHING;
