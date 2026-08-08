-- 护士核对用药医嘱审计：nurse_checked_by / nurse_checked_at 成对出现
-- 未核对以两列均为 NULL 表示，已核对以两列均有值表示
-- 不新增业务状态枚举，medical_orders.status 继续只表达临床生命周期
ALTER TABLE healthcare.medical_orders
    ADD COLUMN nurse_checked_by VARCHAR,
    ADD COLUMN nurse_checked_at TIMESTAMPTZ;

-- 成对约束：两列必须同时为空或同时非空，避免只有单列有值的半核对状态
ALTER TABLE healthcare.medical_orders
    ADD CONSTRAINT ck_medical_orders_nurse_check_pair
    CHECK (
        (nurse_checked_by IS NULL AND nurse_checked_at IS NULL)
        OR (nurse_checked_by IS NOT NULL AND nurse_checked_at IS NOT NULL)
    );

-- 药房待接方读取只返回已核对活动用药医嘱的局部索引
-- 索引列按现有按入住/创建时间查询（encounter_id 过滤 + created_at 倒序）需要
CREATE INDEX IF NOT EXISTS idx_orders_nurse_checked_medication
    ON healthcare.medical_orders (encounter_id, created_at)
    WHERE order_type = 'MEDICATION' AND status = 'ACTIVE' AND nurse_checked_at IS NOT NULL;

COMMENT ON COLUMN healthcare.medical_orders.nurse_checked_by IS '护士核对人（认证主体 userId）；未核对为 NULL，核对后药房才可见并可从该医嘱发药';
COMMENT ON COLUMN healthcare.medical_orders.nurse_checked_at IS '护士核对时间（带时区）；与 nurse_checked_by 成对出现，未核对为 NULL';
