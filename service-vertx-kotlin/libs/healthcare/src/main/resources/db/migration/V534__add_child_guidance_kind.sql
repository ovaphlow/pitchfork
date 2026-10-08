-- =====================================================
-- Healthcare — 儿保健康指导记录增加 kind（V534，053 B3）
--
-- 背景（053 §4 B3 / §11 裁定 5）：
--   医生疾病防控指导复用 052 的指导记录表与 POST 路径，新增 `kind` 区分口径：
--   日常（护士日常指导，052）/ 疾病防控（医生指令，053 B3）。
--
-- 口径：
--   1. `kind` 为中文枚举（日常/疾病防控），由应用层白名单校验，不加 CHECK
--      （沿用 V500/V508/V526/V530「枚举由应用层管控」范式）。
--   2. 默认 `日常`：存量记录与 052 写入（未显式提交 kind）保持日常口径，行为不变。
--   3. `kind` 为业务字段而非权限边界：写路由同路径双码（health:manage / clinical:write，
--      任一放行），集中式声明见 `DeclaredPermissions.kt`。
--   4. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），不重跑 jOOQ codegen
--      （`service-vertx-kotlin/AGENTS.md`「Module Changes」）。
-- =====================================================

SET search_path TO healthcare, public;

ALTER TABLE healthcare.child_health_guidance_records
    ADD COLUMN kind VARCHAR NOT NULL DEFAULT '日常';

COMMENT ON COLUMN healthcare.child_health_guidance_records.kind IS '指导口径中文枚举：日常（052 护士日常指导）/疾病防控（053 B3 医生指令）；应用层白名单，默认日常';
