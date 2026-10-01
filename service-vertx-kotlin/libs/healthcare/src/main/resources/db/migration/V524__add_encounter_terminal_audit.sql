-- =====================================================
-- Healthcare — 离院/去世操作人留痕（V524）
--
-- 背景：离院与去世都是不可撤销的高危终局动作，但 encounters 此前只写
-- discharge_date / death_date（业务日期，可回填）与 updated_at（技术时间，后续
-- 任何 PUT 都会刷新），无法回答「谁在什么时间确认了这次离院/去世」。
--
-- 本迁移只给既有 encounters 表加四列，不新增表、不改状态枚举、不回填历史数据：
--   discharged_by  离院操作人（认证主体 subject_id，写在离院事务内）
--   discharged_at  离院确认时刻（服务端 now，非客户端的离院业务日期）
--   deceased_by    去世操作人（认证主体 subject_id）
--   deceased_at    去世确认时刻（服务端 now，非客户端的去世业务日期）
--
-- 口径：
--   1. discharge_date / death_date 是**业务日期**，允许由操作者回填/回溯；
--      discharged_at / deceased_at 是**确认时刻**，只由服务端写入，二者不可混用。
--   2. 操作人取认证中间件写入的 userId（同 attending_physician 口径），
--      客户端不得通过请求体提交；请求体伪造值不被采纳。
--   3. 历史行与未认证挂载（仅嵌入式测试）下 *_by 允许为空；*_at 在终局写入时总是
--      有值。生产环境所有 /crate-api/* 都经过 fail-closed 认证总闸，*_by 必然有值。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

ALTER TABLE healthcare.encounters
    ADD COLUMN discharged_by VARCHAR,
    ADD COLUMN discharged_at TIMESTAMPTZ,
    ADD COLUMN deceased_by   VARCHAR,
    ADD COLUMN deceased_at   TIMESTAMPTZ;

COMMENT ON COLUMN encounters.discharged_by IS '离院操作人（认证主体 subject_id），客户端不得提交；历史行与未认证挂载可为空';
COMMENT ON COLUMN encounters.discharged_at IS '离院确认时刻（服务端 now）；与离院业务日期 discharge_date 区分，后者可由操作者回填';
COMMENT ON COLUMN encounters.deceased_by IS '去世操作人（认证主体 subject_id），客户端不得提交；历史行与未认证挂载可为空';
COMMENT ON COLUMN encounters.deceased_at IS '去世确认时刻（服务端 now）；与去世业务日期 death_date 区分，后者可由操作者回填';