-- =====================================================
-- Healthcare — 儿保体检诊断字段 + 发育筛查联动列（V532，053 B1；筛查列 A3 复用）
--
-- 背景（053 §4 B1 / §11 裁定 2）：
--   052 的 `child_health_checkups` 只有 `items`/`conclusion`，医生无法回写诊断。
--   053 Da/Dc 决定在体检记录上扩展诊断字段，**不放宽 `diagnoses` 的 `ELDERLY_CARE` 限制**。
--
-- 口径：
--   1. `diagnosis` / `diagnosis_code` 由医生诊断接口（B1）回写；`reviewed_by` / `reviewed_at`
--      由服务端按认证主体与当前时间派生，客户端不得提交（应用层写白名单拒绝）。
--   2. `status` 为中文枚举 待诊断/已完成（应用层白名单，加单向流转：本接口只写「已完成」，
--      无回到「待诊断」的入参），沿用 V500/V508/V526「枚举由应用层管控、不加 CHECK」范式。
--      `DEFAULT '待诊断'`：存量与新建记录初始为「待诊断」。
--   3. `checkup_id` 由 053 A3 复用于「筛查结论关联体检记录」，可空（本任务只建列）。
--   4. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不重跑 jOOQ codegen**
--      （`service-vertx-kotlin/AGENTS.md`「Module Changes」）。
-- =====================================================

SET search_path TO healthcare, public;

ALTER TABLE healthcare.child_health_checkups
    ADD COLUMN diagnosis TEXT,                                    -- 医生诊断（053 B1 回写）
    ADD COLUMN diagnosis_code VARCHAR,                            -- 诊断编码（053 B1 回写，可空）
    ADD COLUMN status VARCHAR NOT NULL DEFAULT '待诊断',          -- 待诊断 / 已完成（应用层白名单，单向流转）
    ADD COLUMN reviewed_by VARCHAR,                               -- 诊断/审阅医生（认证主体），客户端不得提交
    ADD COLUMN reviewed_at TIMESTAMPTZ;                           -- 诊断时间，服务端派生

COMMENT ON COLUMN healthcare.child_health_checkups.diagnosis IS '医生诊断（053 B1 回写，可空）';
COMMENT ON COLUMN healthcare.child_health_checkups.diagnosis_code IS '诊断编码（053 B1 回写，可空）';
COMMENT ON COLUMN healthcare.child_health_checkups.status IS '诊断状态中文枚举：待诊断/已完成（应用层白名单，单向流转，不加 CHECK）';
COMMENT ON COLUMN healthcare.child_health_checkups.reviewed_by IS '诊断/审阅医生，来自认证主体，客户端不得提交';
COMMENT ON COLUMN healthcare.child_health_checkups.reviewed_at IS '诊断时间，服务端派生，客户端不得提交';

-- 053 A3 复用：筛查结论关联体检记录（可空，本任务只建列）。
ALTER TABLE healthcare.developmental_screenings
    ADD COLUMN checkup_id VARCHAR REFERENCES healthcare.child_health_checkups(id);

COMMENT ON COLUMN healthcare.developmental_screenings.checkup_id IS '关联体检记录，053 A3 复用于筛查结论联动（可空）';
