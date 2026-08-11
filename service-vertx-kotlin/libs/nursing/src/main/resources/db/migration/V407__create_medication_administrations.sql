-- =====================================================
-- 医嘱执行闭环：护士给药记录（MAR）
-- Schema: nursing（给药是护理执行环节的事实，挂在 nursing_task_executions 下）
-- 号段：V407（nursing 段内空号，V400–V406/V408 不动）
--
-- 与护理执行 1:1（task_execution_id UNIQUE）：
--   已服/部分服 → 执行 COMPLETED，消耗发药数量（必须引用 DISPENSED 发药明细）
--   拒服/漏服/暂缓 → 执行 SKIPPED，不消耗数量、不要求发药来源
--
-- 给药不扣库存：发药确认（DISPENSED）时已出库，这里只做批次追溯与数量对账，
-- 避免与 002 耗材消耗、011 发药出库形成双重扣减。
-- =====================================================

CREATE TABLE nursing.medication_administrations (
    id                    VARCHAR(32) PRIMARY KEY,
    task_execution_id     VARCHAR(32) NOT NULL UNIQUE REFERENCES nursing.nursing_task_executions(id),
    medical_order_id      VARCHAR(32) NOT NULL,          -- 冗余医嘱归属（弱关联），查询隔离用
    dispense_item_id      VARCHAR(32),                   -- 发药明细来源（已服/部分服必填，弱关联）
    lot_id                VARCHAR(32),                   -- 批次快照
    warehouse             VARCHAR,                       -- 仓库快照
    result                VARCHAR NOT NULL CHECK (result IN ('已服','部分服','拒服','漏服','暂缓')),
    administered_quantity NUMERIC(20,6),                 -- 实际给药数量（基础单位，016 单一基础单位模型）
    unit                  VARCHAR,                       -- 单位快照（取自医嘱明细 dose 单位）
    administered_by       VARCHAR NOT NULL,              -- 给药人（认证 userId，服务端写入）
    administered_at       TIMESTAMPTZ NOT NULL,          -- 给药时间（服务端当前时间，不接受客户端传入）
    reason                TEXT,                          -- 拒服/漏服/暂缓/部分服原因
    created_at            TIMESTAMPTZ DEFAULT now(),
    updated_at            TIMESTAMPTZ DEFAULT now(),
    -- 结果一致性：已服/部分服必须带来源与正数量；拒服/漏服/暂缓不得带来源与数量
    CONSTRAINT ck_medication_administrations_result_consistency CHECK (
        (result IN ('已服','部分服') AND dispense_item_id IS NOT NULL
            AND administered_quantity IS NOT NULL AND administered_quantity > 0)
        OR
        (result IN ('拒服','漏服','暂缓') AND dispense_item_id IS NULL
            AND administered_quantity IS NULL)
    ),
    -- 原因必填：部分服/拒服/漏服/暂缓；已服原因可选
    CONSTRAINT ck_medication_administrations_reason CHECK (
        result = '已服'
        OR (reason IS NOT NULL AND length(btrim(reason)) > 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_ma_medical_order ON nursing.medication_administrations(medical_order_id);
CREATE INDEX IF NOT EXISTS idx_ma_dispense_item ON nursing.medication_administrations(dispense_item_id);
CREATE INDEX IF NOT EXISTS idx_ma_administered_at ON nursing.medication_administrations(administered_at);

COMMENT ON TABLE nursing.medication_administrations IS '护士给药记录（MAR）：每执行实例至多一条，与药房发药明细联动对账（累计给药 ≤ 实发数量）';
COMMENT ON COLUMN nursing.medication_administrations.administered_by IS '给药人（认证主体 userId），服务端写入，不接受客户端伪造';
COMMENT ON COLUMN nursing.medication_administrations.administered_at IS '给药时间（服务端当前时间），不接受客户端传入';
