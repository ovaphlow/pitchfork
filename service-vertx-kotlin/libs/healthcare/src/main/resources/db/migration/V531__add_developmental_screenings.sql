-- =====================================================
-- Healthcare — 儿童发育筛查结果（V531，053 A2）
--
-- 背景（053 §4 A2 / §11 裁定 3）：
--   A1 已交付声明式发育量表包与评分引擎（`DevelopmentalScale.kt`，纯逻辑、无 DB）。
--   本表持久化一次筛查的「量表 + 月龄 + 条目得分 + 领域分 + 总分 + 判读」结果。
--
-- 口径：
--   1. `scale_version` / `age_months` / `domain_scores` / `total_score` / `classification`
--      一律由服务端调用 A1 评分引擎派生落库，客户端不得提交（写白名单拒绝）。
--   2. `classification` = 正常/可疑/异常（中文枚举，应用层白名单，沿用 V500/V508/V526
--      「枚举由应用层管控、不加 CHECK」范式）。
--   3. `conclusion`（医生判读）本任务只建列；写接口由 053 A3 提供。
--   4. `item_scores` 存引擎回显的「条目编码 → 分值」（按适龄条目顺序的 JSONB 对象）。
--   5. `metadata` 额外存 `domain_max_scores` 与 `triggered_items`，供读接口回显（不新增列）。
--   6. `operator` 一律取认证主体，客户端不得提交。
--   7. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不重跑 jOOQ codegen**
--      （`service-vertx-kotlin/AGENTS.md`「Module Changes」）。
--   8. 053 A3 复用本表加 `checkup_id`（V532）。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE developmental_screenings (
    id                VARCHAR(32) PRIMARY KEY,
    patient_id        VARCHAR(32) NOT NULL REFERENCES patients(id),
    scale_code        VARCHAR NOT NULL,                         -- A1 量表编码（如 CHILD_DEV_PLACEHOLDER）
    scale_version     VARCHAR NOT NULL,                         -- 量表版本，服务端按 A1 引擎派生
    age_months        INTEGER NOT NULL,                         -- 筛查时月龄，服务端派生
    administered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),       -- 施测时间；不得晚于当前
    item_scores       JSONB NOT NULL DEFAULT '{}',              -- 条目编码 → 分值（按适龄条目顺序）
    domain_scores     JSONB NOT NULL DEFAULT '{}',              -- 领域编码 → 得分，服务端派生
    total_score       INTEGER NOT NULL,                         -- 总分，服务端派生
    classification    VARCHAR NOT NULL,                         -- 正常 / 可疑 / 异常（应用层白名单）
    conclusion        TEXT,                                     -- 医生判读（053 A3 写入，本任务不提供写接口）
    operator          VARCHAR NOT NULL,                         -- 操作人（认证主体），客户端不得提交
    metadata          JSONB,                                    -- 扩展：domain_max_scores / triggered_items 等
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE developmental_screenings IS '儿童发育筛查结果：量表 + 月龄的筛查记录（053 A2；A3 复用本表加 checkup_id）';
COMMENT ON COLUMN developmental_screenings.scale_version IS '量表版本，由服务端调用 A1 评分引擎派生，客户端不得提交';
COMMENT ON COLUMN developmental_screenings.age_months IS '筛查时月龄，由服务端按 patients.birth_date 与 administered_at 业务日（Asia/Shanghai）派生';
COMMENT ON COLUMN developmental_screenings.item_scores IS '条目编码 → 分值，按适龄条目顺序（A1 引擎回显）';
COMMENT ON COLUMN developmental_screenings.domain_scores IS '领域编码 → 得分，由服务端调用 A1 评分引擎派生，客户端不得提交';
COMMENT ON COLUMN developmental_screenings.classification IS '判读中文枚举：正常/可疑/异常（应用层白名单，不加 CHECK）';
COMMENT ON COLUMN developmental_screenings.conclusion IS '医生判读结论，053 A3 提供写接口；本任务只建列';
COMMENT ON COLUMN developmental_screenings.operator IS '操作人，来自认证主体，客户端不得提交';
COMMENT ON COLUMN developmental_screenings.metadata IS '扩展 JSONB：存 domain_max_scores / triggered_items，供读接口回显';

CREATE INDEX IF NOT EXISTS idx_developmental_screenings_patient_at
    ON developmental_screenings(patient_id, administered_at DESC);
CREATE INDEX IF NOT EXISTS idx_developmental_screenings_patient_classification
    ON developmental_screenings(patient_id, classification);
