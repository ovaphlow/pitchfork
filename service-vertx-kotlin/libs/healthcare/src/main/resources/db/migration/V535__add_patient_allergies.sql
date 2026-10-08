-- =====================================================
-- Healthcare — 患者药物过敏史规范化（V535，053 D1）
--
-- 背景（053 §4 D1 / §3 Df）：
--   既有 `patients.allergies` JSONB（V500，形状 `[{"allergen","reaction"}]`）只存不查。
--   本表把过敏史规范化为独立行，供 D2 过敏/禁忌检查引擎与开嘱提醒消费。
--
-- 口径：
--   1. `allergen_type` = 药物/食物/其他；`severity` = 一般/严重；`status` = 有效/已停用。
--      全部中文枚举由应用层白名单校验（沿用 V500/V508/V526「枚举由应用层管控、不加 CHECK」范式）。
--   2. 一次性回填 `patients.allergies` JSONB：`jsonb_typeof(allergies)='array'` 且长度 > 0，
--      逐元素展开为一行：`allergen_type='其他'`（遗留无类型）、`allergen_name=allergen`、
--      `reaction=reaction`、`severity='一般'`、`status='有效'`、`operator='system:backfill'`；
--      id = substr(upper(md5(patient_id || ':' || 元素序号)), 1, 26)（26 位、落在 VARCHAR(32)、
--      字符属 Crockford Base32 合法集）；`allergen_name` 为空/缺失的元素跳过。
--   3. **保留 `patients.allergies` JSONB 为只读遗留**，回填后不清空；D1 之后的读写一律走本表。
--   4. `operator` 一律取认证主体，客户端不得提交；回填行 operator 固定 `system:backfill`。
--   5. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不重跑 jOOQ codegen**
--      （`service-vertx-kotlin/AGENTS.md`「Module Changes」）。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE healthcare.patient_allergies (
    id                VARCHAR(32) PRIMARY KEY,
    patient_id        VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    allergen_type     VARCHAR NOT NULL,                     -- 药物/食物/其他（应用层白名单）
    allergen_name     VARCHAR NOT NULL,                     -- 过敏原名称
    allergen_code     VARCHAR,                              -- 过敏原编码（如药品编码，可空）
    reaction          TEXT,                                 -- 过敏反应描述
    severity          VARCHAR NOT NULL DEFAULT '一般',       -- 一般/严重（应用层白名单）
    status            VARCHAR NOT NULL DEFAULT '有效',       -- 有效/已停用（软删置「已停用」）
    operator          VARCHAR NOT NULL,                     -- 操作人（认证主体），客户端不得提交
    metadata          JSONB,                                -- 扩展
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE healthcare.patient_allergies IS '患者药物过敏史规范化（053 D1）：由 patients.allergies JSONB 回填，供过敏/禁忌检查消费';
COMMENT ON COLUMN healthcare.patient_allergies.allergen_type IS '过敏原类型中文枚举：药物/食物/其他（应用层白名单，不加 CHECK）';
COMMENT ON COLUMN healthcare.patient_allergies.allergen_code IS '过敏原编码（如药品编码），可空';
COMMENT ON COLUMN healthcare.patient_allergies.severity IS '严重度中文枚举：一般/严重（应用层白名单，不加 CHECK）';
COMMENT ON COLUMN healthcare.patient_allergies.status IS '状态中文枚举：有效/已停用；软删置「已停用」，不加 CHECK';
COMMENT ON COLUMN healthcare.patient_allergies.operator IS '操作人，来自认证主体，客户端不得提交；回填行为 system:backfill';
COMMENT ON COLUMN healthcare.patient_allergies.metadata IS '扩展 JSONB';

CREATE INDEX IF NOT EXISTS idx_patient_allergies_patient_status
    ON healthcare.patient_allergies(patient_id, status);
CREATE INDEX IF NOT EXISTS idx_patient_allergies_patient_name
    ON healthcare.patient_allergies(patient_id, allergen_name);

-- 一次性回填：patients.allergies JSONB（只读遗留）→ 规范化行。
-- 非数组（含默认 '[]'）在 CTE 内先滤掉，避免 jsonb_array_elements 对非数组报错。
WITH src AS (
    SELECT id, allergies
    FROM healthcare.patients
    WHERE jsonb_typeof(allergies) = 'array'
      AND jsonb_array_length(
              CASE WHEN jsonb_typeof(allergies) = 'array' THEN allergies ELSE '[]'::jsonb END
          ) > 0
)
INSERT INTO healthcare.patient_allergies
    (id, patient_id, allergen_type, allergen_name, allergen_code, reaction,
     severity, status, operator, metadata, created_at, updated_at)
SELECT
    substr(upper(md5(p.id || ':' || e.ordinality)), 1, 26),
    p.id,
    '其他',
    e.value ->> 'allergen',
    NULL,
    e.value ->> 'reaction',
    '一般',
    '有效',
    'system:backfill',
    NULL,
    now(),
    now()
FROM src p
CROSS JOIN LATERAL jsonb_array_elements(p.allergies) WITH ORDINALITY AS e(value, ordinality)
WHERE NULLIF(btrim(e.value ->> 'allergen'), '') IS NOT NULL;
