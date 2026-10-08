-- =====================================================
-- Healthcare — 儿童健康问题转诊记录（V533，053 B2）
--
-- 背景（053 §4 B2 / §3 Dd）：
--   健康问题转诊（体检/筛查/异常/手动来源）落独立表，状态机
--   待转诊 → 已转诊 → 已完成（+已取消），由应用层白名单管控；不强制关联 encounter
--   （延续 052 口径）。
--
-- 口径：
--   1. `source_type` = 体检/筛查/异常/手动；`referral_type` = 院内转诊/转上级医院/专科转诊；
--      `urgency` = 常规/急；`status` = 待转诊/已转诊/已完成/已取消。
--      全部中文枚举由应用层白名单校验（沿用 V500/V508/V526「枚举由应用层管控、不加 CHECK」范式）。
--   2. 状态机由应用层白名单管控：待转诊 → {已转诊, 已取消}；已转诊 → {已完成}；
--      已完成 / 已取消为终态。非法流转由服务层拒绝（400），不落 CHECK 约束。
--   3. `referred_at` 在转入「已转诊」时由服务端缺省 now（不得晚于现在）。
--   4. `operator` 一律取认证主体，客户端不得提交。
--   5. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不重跑 jOOQ codegen**
--      （`service-vertx-kotlin/AGENTS.md`「Module Changes」）。
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE child_referrals (
    id                VARCHAR(32) PRIMARY KEY,
    patient_id        VARCHAR(32) NOT NULL REFERENCES patients(id),
    source_type       VARCHAR NOT NULL,                     -- 体检/筛查/异常/手动（应用层白名单）
    source_id         VARCHAR,                              -- 来源记录 id（可空：手动来源无来源记录）
    problem_summary   TEXT NOT NULL,                        -- 健康问题摘要
    referral_type     VARCHAR NOT NULL,                     -- 院内转诊/转上级医院/专科转诊（应用层白名单）
    target_org        VARCHAR,                              -- 转诊目标机构
    target_dept       VARCHAR,                              -- 转诊目标科室
    urgency           VARCHAR NOT NULL DEFAULT '常规',       -- 常规/急（应用层白名单）
    status            VARCHAR NOT NULL DEFAULT '待转诊',     -- 待转诊/已转诊/已完成/已取消（应用层状态机）
    referred_at       TIMESTAMPTZ,                          -- 转出时间：转入「已转诊」时服务端缺省 now
    outcome           TEXT,                                 -- 转诊结局（完成时回写）
    operator          VARCHAR NOT NULL,                     -- 操作人（认证主体），客户端不得提交
    metadata          JSONB,                                -- 扩展
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE child_referrals IS '儿童健康问题转诊记录：体检/筛查/异常/手动来源的转诊与状态机（053 B2）';
COMMENT ON COLUMN child_referrals.source_type IS '来源中文枚举：体检/筛查/异常/手动（应用层白名单）';
COMMENT ON COLUMN child_referrals.referral_type IS '转诊类型中文枚举：院内转诊/转上级医院/专科转诊（应用层白名单）';
COMMENT ON COLUMN child_referrals.urgency IS '紧急度中文枚举：常规/急（应用层白名单）';
COMMENT ON COLUMN child_referrals.status IS '状态中文枚举：待转诊/已转诊/已完成/已取消；流转由应用层白名单管控，不加 CHECK';
COMMENT ON COLUMN child_referrals.referred_at IS '转出时间：转入「已转诊」时服务端缺省 now，不得晚于现在';
COMMENT ON COLUMN child_referrals.operator IS '操作人，来自认证主体，客户端不得提交';
COMMENT ON COLUMN child_referrals.metadata IS '扩展 JSONB';

CREATE INDEX IF NOT EXISTS idx_child_referrals_patient_created
    ON child_referrals(patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_child_referrals_patient_status
    ON child_referrals(patient_id, status);
