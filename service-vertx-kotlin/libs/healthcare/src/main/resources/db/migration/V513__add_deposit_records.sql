-- =====================================================
-- Healthcare — 押金台账（Deposit Records）
-- 养老费用管理的独立子任务：入住押金登记、退押与台账，挂 encounter
-- （不强制关联费用项目字典；结算收束不自动冲抵押金）。
-- 设计原则：
--   1. 登记与退押为同表两类记录：type 中文枚举（登记/退押），
--      应用层白名单管控，不加 CHECK 约束（V500 约定）
--   2. amount NUMERIC(12,2)，登记/退押均为正数；余额 = Σ登记 − Σ退押，
--      退押不得超余额（应用层校验，余额不为负）
--   3. 退押为独立操作：不校验 encounter 收束状态，离院/去世后仍可退押
--   4. operator 一律取认证主体，客户端不得提交；记录为台账语义，不可更新
-- =====================================================

SET search_path TO healthcare, public;

CREATE TABLE deposit_records (
    id              VARCHAR(32) PRIMARY KEY,          -- ULID
    encounter_id    VARCHAR(32) NOT NULL REFERENCES encounters(id),
    type            VARCHAR NOT NULL,                 -- 登记 / 退押（中文枚举，应用层管控）
    amount          NUMERIC(12,2) NOT NULL,           -- 发生金额（元），正数由应用层保证
    operator        VARCHAR NOT NULL,                 -- 操作人（认证主体），客户端不得提交
    remark          VARCHAR,                          -- 备注
    metadata        JSONB,                            -- 扩展元数据
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
COMMENT ON TABLE deposit_records IS '押金台账：入住押金登记与退押记录（养老费用管理）';
COMMENT ON COLUMN deposit_records.encounter_id IS '挂 encounter（入住周期），不强制关联费用项目字典';
COMMENT ON COLUMN deposit_records.type IS '记录类型中文枚举：登记/退押（应用层白名单管控）';
COMMENT ON COLUMN deposit_records.amount IS '发生金额（元）：登记与退押均为正数，余额=Σ登记−Σ退押，余额不为负';
COMMENT ON COLUMN deposit_records.operator IS '操作人，来自认证主体，客户端不得提交';
COMMENT ON COLUMN deposit_records.remark IS '备注（登记/退押原因等）';
COMMENT ON COLUMN deposit_records.metadata IS '扩展元数据';

-- 台账按 encounter 倒序读取（应用层分页 + 余额聚合）
CREATE INDEX IF NOT EXISTS idx_deposit_records_encounter
    ON deposit_records(encounter_id, created_at DESC, id DESC);
