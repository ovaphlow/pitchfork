-- =====================================================
-- V202: 库存基础计量单位与包装换算演进
--
-- 依据：docs/plans/015.aceso-inventory-base-unit-and-packaging-conversion.md
-- 原则：
--   * 不改写 V200/V201；所有历史列保留为只读兼容来源
--   * 新写入以基础单位（base_quantity 等）为权威事实
--   * 仅对可无歧义换算的历史数据回填；不安全的历史行写入
--     inventory_unit_migration_issues 并将物资标为 MIGRATION_BLOCKED
--   * 金额列只扩精度（NUMERIC(18,4) -> NUMERIC(24,8)），不截断历史值
-- =====================================================

SET search_path TO public;

-- =====================================================
-- 1. materials：基础单位、精度与计量模型状态
-- =====================================================

ALTER TABLE materials
    ADD COLUMN base_unit VARCHAR,
    ADD COLUMN base_quantity_scale SMALLINT NOT NULL DEFAULT 0
        CHECK (base_quantity_scale BETWEEN 0 AND 6),
    ADD COLUMN unit_model_status VARCHAR NOT NULL DEFAULT 'LEGACY'
        CHECK (unit_model_status IN ('LEGACY', 'ACTIVE', 'MIGRATION_BLOCKED')),
    ADD COLUMN updated_at TIMESTAMPTZ;

-- =====================================================
-- 2. material_unit_specs：包装规格表
--    每项物资恰好一条基础规格（is_base_unit），最多一条活动默认规格
-- =====================================================

CREATE TABLE IF NOT EXISTS material_unit_specs
(
    id              VARCHAR(32) PRIMARY KEY,
    material_id     VARCHAR(32) NOT NULL REFERENCES materials(id),
    input_unit      VARCHAR     NOT NULL,
    base_ratio      NUMERIC(20,6) NOT NULL CHECK (base_ratio > 0),
    is_base_unit    BOOLEAN     NOT NULL DEFAULT FALSE,
    is_default      BOOLEAN     NOT NULL DEFAULT FALSE,
    status          VARCHAR     NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retired_at      TIMESTAMPTZ
);

-- 每项物资最多一条基础规格
CREATE UNIQUE INDEX IF NOT EXISTS uq_mus_base_unit_per_material
    ON material_unit_specs(material_id)
    WHERE is_base_unit;

-- 每项物资最多一条 ACTIVE 默认规格
CREATE UNIQUE INDEX IF NOT EXISTS uq_mus_active_default_per_material
    ON material_unit_specs(material_id)
    WHERE is_default AND status = 'ACTIVE';

-- 物资查询索引
CREATE INDEX IF NOT EXISTS idx_mus_material_status
    ON material_unit_specs(material_id, status);

-- =====================================================
-- 3. inventory_unit_migration_issues：迁移异常记录
-- =====================================================

CREATE TABLE IF NOT EXISTS inventory_unit_migration_issues
(
    id           VARCHAR(32) PRIMARY KEY,
    object_type  VARCHAR     NOT NULL,
    object_id    VARCHAR(32) NOT NULL,
    material_id  VARCHAR(32) REFERENCES materials(id),
    reason_code  VARCHAR     NOT NULL,
    detail       VARCHAR,
    status       VARCHAR     NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED', 'WAIVED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ,
    resolved_by  VARCHAR
);

CREATE INDEX IF NOT EXISTS idx_migration_issues_material
    ON inventory_unit_migration_issues(material_id, status);

CREATE INDEX IF NOT EXISTS idx_migration_issues_object
    ON inventory_unit_migration_issues(object_type, object_id);

-- =====================================================
-- 4. stocks：基础结存与锁定量
-- =====================================================

ALTER TABLE stocks
    ADD COLUMN base_quantity NUMERIC(20,6),
    ADD COLUMN locked_base_quantity NUMERIC(20,6),
    ADD COLUMN unit_model_status VARCHAR NOT NULL DEFAULT 'LEGACY'
        CHECK (unit_model_status IN ('LEGACY', 'ACTIVE', 'MIGRATION_BLOCKED'));

-- 基础结存 >= 基础锁定 >= 0
ALTER TABLE stocks
    ADD CONSTRAINT ck_stocks_base_ge_locked
        CHECK (base_quantity IS NULL
               OR (locked_base_quantity IS NOT NULL
                   AND base_quantity >= locked_base_quantity
                   AND locked_base_quantity >= 0));

-- 金额列只扩精度
ALTER TABLE stocks
    ALTER COLUMN total_cost TYPE NUMERIC(24,8);

-- 基础可用量查询索引
CREATE INDEX IF NOT EXISTS idx_stocks_base_available
    ON stocks(warehouse, material_id)
    WHERE base_quantity > locked_base_quantity;

-- =====================================================
-- 5. stock_operation_details：不可变换算快照列
-- =====================================================

ALTER TABLE stock_operation_details
    ADD COLUMN unit_spec_id VARCHAR(32),
    ADD COLUMN input_quantity NUMERIC(20,6),
    ADD COLUMN input_unit VARCHAR,
    ADD COLUMN conversion_ratio NUMERIC(20,6),
    ADD COLUMN base_quantity NUMERIC(20,6),
    ADD COLUMN base_unit VARCHAR,
    ADD COLUMN input_unit_cost NUMERIC(24,8),
    ADD COLUMN base_unit_cost NUMERIC(24,8);

-- 金额列只扩精度
ALTER TABLE stock_operation_details
    ALTER COLUMN unit_cost TYPE NUMERIC(24,8),
    ALTER COLUMN total_cost TYPE NUMERIC(24,8);

-- 基础数量查询索引
CREATE INDEX IF NOT EXISTS idx_sod_base_material
    ON stock_operation_details(material_id, base_quantity)
    WHERE base_quantity IS NOT NULL;

-- =====================================================
-- 6. 回填辅助：会话级 Crockford Base32 ULID 生成器
--    与 com.ovaphlow.crate.common.Ulid 同构（10 位时间 + 16 位随机）
-- =====================================================

CREATE OR REPLACE FUNCTION pg_temp.crate_ulid() RETURNS VARCHAR AS $$
DECLARE
    alphabet CONSTANT VARCHAR := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    ms BIGINT;
    time_part VARCHAR := '';
    rand_part VARCHAR := '';
    n NUMERIC := 0;
    i INT;
    b BYTEA;
BEGIN
    ms := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
    FOR i IN 1..10 LOOP
        time_part := substr(alphabet, (ms % 32)::INT + 1, 1) || time_part;
        ms := ms / 32;
    END LOOP;
    b := decode(md5(clock_timestamp()::TEXT || random()::TEXT), 'hex');
    FOR i IN 1..10 LOOP
        n := n * 256 + get_byte(b, i - 1);
    END LOOP;
    FOR i IN 1..16 LOOP
        rand_part := substr(alphabet, (n % 32)::INT + 1, 1) || rand_part;
        n := floor(n / 32);
    END LOOP;
    RETURN time_part || rand_part;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 7. 回填物资计量模型（确定性顺序，见计划 4.3.1-4.3.2）
-- =====================================================

-- 7.1 有效拆零定义：base_unit = split_unit
UPDATE materials
SET base_unit           = split_unit,
    unit_model_status   = 'ACTIVE',
    updated_at          = NOW()
WHERE split_unit IS NOT NULL
  AND split_ratio IS NOT NULL
  AND split_ratio > 0;

-- 7.2 无拆零定义：base_unit = package_unit
UPDATE materials
SET base_unit           = package_unit,
    unit_model_status   = 'ACTIVE',
    updated_at          = NOW()
WHERE base_unit IS NULL
  AND split_unit IS NULL
  AND package_unit IS NOT NULL;

-- 7.3 其余（拆零单位存在但比率无效 / 缺失单位）：阻断，等待人工确认
UPDATE materials
SET unit_model_status   = 'MIGRATION_BLOCKED',
    updated_at          = NOW()
WHERE base_unit IS NULL;

INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'MATERIAL', id, id, 'MATERIAL_NO_BASE_UNIT',
       '物资缺失可用的基础单位定义，无法无歧义换算', 'OPEN', NOW()
FROM materials
WHERE unit_model_status = 'MIGRATION_BLOCKED';

-- 比率无效的拆零定义单独记录原因
INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'MATERIAL', id, id, 'INVALID_SPLIT_RATIO',
       'split_unit 非空但 split_ratio 缺失或非正，视为无效换算定义', 'OPEN', NOW()
FROM materials
WHERE unit_model_status = 'MIGRATION_BLOCKED'
  AND split_unit IS NOT NULL
  AND (split_ratio IS NULL OR split_ratio <= 0);

-- =====================================================
-- 8. 回填规格（仅 ACTIVE 物资）
--    基础规格：input_unit = base_unit, base_ratio = 1, is_base_unit
--    默认规格：旧 PACKAGE 端口映射
--        * 有效拆零：input_unit = package_unit, base_ratio = split_ratio
--        * 无拆零：  input_unit = package_unit, base_ratio = 1
-- =====================================================

INSERT INTO material_unit_specs
    (id, material_id, input_unit, base_ratio, is_base_unit, is_default, status, created_at)
SELECT pg_temp.crate_ulid(), id, base_unit, 1, TRUE, FALSE, 'ACTIVE', NOW()
FROM materials
WHERE unit_model_status = 'ACTIVE';

INSERT INTO material_unit_specs
    (id, material_id, input_unit, base_ratio, is_base_unit, is_default, status, created_at)
SELECT pg_temp.crate_ulid(),
       id,
       package_unit,
       CASE WHEN split_unit IS NOT NULL AND split_ratio > 0 THEN split_ratio ELSE 1 END,
       FALSE, TRUE, 'ACTIVE', NOW()
FROM materials
WHERE unit_model_status = 'ACTIVE'
  AND package_unit IS NOT NULL;

-- =====================================================
-- 9. 回填确认流水的不可变换算快照（仅 ACTIVE 物资）
--    PACKAGE: 输入快照 = 原 quantity + 默认包装规格，
--             base_quantity = quantity * base_ratio
--    SPLIT:   输入快照 = split_quantity + 基础单位，conversion_ratio = 1，
--             base_quantity = split_quantity
--    SPLIT 缺少有效 split_quantity -> 不填快照并记异常
-- =====================================================

UPDATE stock_operation_details d
SET unit_spec_id      = spec.id,
    input_quantity    = d.quantity,
    input_unit        = spec.input_unit,
    conversion_ratio  = spec.base_ratio,
    base_quantity     = d.quantity * spec.base_ratio,
    base_unit         = m.base_unit,
    input_unit_cost   = d.unit_cost,
    base_unit_cost    = d.unit_cost / spec.base_ratio
FROM materials m
JOIN material_unit_specs spec
  ON spec.material_id = m.id AND spec.is_default AND spec.status = 'ACTIVE'
WHERE d.material_id = m.id
  AND m.unit_model_status = 'ACTIVE'
  AND d.unit = 'PACKAGE';

UPDATE stock_operation_details d
SET unit_spec_id      = base_spec.id,
    input_quantity    = d.split_quantity,
    input_unit        = m.base_unit,
    conversion_ratio  = 1,
    base_quantity     = d.split_quantity,
    base_unit         = m.base_unit,
    input_unit_cost   = d.unit_cost,
    base_unit_cost    = d.unit_cost / default_spec.base_ratio
FROM materials m
JOIN material_unit_specs base_spec
  ON base_spec.material_id = m.id AND base_spec.is_base_unit AND base_spec.status = 'ACTIVE'
JOIN material_unit_specs default_spec
  ON default_spec.material_id = m.id AND default_spec.is_default AND default_spec.status = 'ACTIVE'
WHERE d.material_id = m.id
  AND m.unit_model_status = 'ACTIVE'
  AND d.unit = 'SPLIT'
  AND d.split_quantity IS NOT NULL;

-- SPLIT 明细缺少有效 split_quantity：记异常，快照保持 NULL
INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'STOCK_OPERATION_DETAIL', d.id, d.material_id,
       'SPLIT_MISSING_QUANTITY',
       'SPLIT 明细缺少有效 split_quantity，无法确定基础数量', 'OPEN', NOW()
FROM stock_operation_details d
JOIN stock_operations o ON o.id = d.operation_id
WHERE d.unit = 'SPLIT'
  AND d.split_quantity IS NULL;

-- 精度校验：基础数量必须符合物资精度（迁移阶段精度均为默认 0，非整数即不符）
INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'STOCK_OPERATION_DETAIL', d.id, d.material_id,
       'PRECISION_VIOLATION',
       '换算后的基础数量不符合物资 base_quantity_scale 允许的精度', 'OPEN', NOW()
FROM stock_operation_details d
JOIN materials m ON m.id = d.material_id
WHERE d.base_quantity IS NOT NULL
  AND (m.base_quantity_scale = 0
       AND d.base_quantity <> floor(d.base_quantity));

-- =====================================================
-- 10. 有异常的物资整体阻断：清空其快照，保持只读历史
-- =====================================================

UPDATE materials m
SET unit_model_status = 'MIGRATION_BLOCKED',
    updated_at        = NOW()
WHERE m.unit_model_status = 'ACTIVE'
  AND EXISTS (
      SELECT 1 FROM inventory_unit_migration_issues i
      WHERE i.material_id = m.id AND i.status = 'OPEN'
  );

UPDATE stock_operation_details d
SET unit_spec_id = NULL, input_quantity = NULL, input_unit = NULL,
    conversion_ratio = NULL, base_quantity = NULL, base_unit = NULL,
    input_unit_cost = NULL, base_unit_cost = NULL
FROM materials m
WHERE d.material_id = m.id
  AND m.unit_model_status = 'MIGRATION_BLOCKED';

-- =====================================================
-- 11. 回填库存结存（仅 ACTIVE 物资）
--     基础结存 = 历史确认明细的可回放基础数量之和（INBOUND - OUTBOUND）
--     可回放一致要求：存在明细、明细均已有基础数量，且
--         replay_base = quantity * 默认规格 base_ratio
--     基础锁定 = locked_quantity * 默认规格 base_ratio（须符合精度）
-- =====================================================

WITH replay AS (
    SELECT d.material_id,
           d.lot_id,
           COALESCE(SUM(d.base_quantity) FILTER (WHERE o.operation_type = 'INBOUND'), 0)
               - COALESCE(SUM(d.base_quantity) FILTER (WHERE o.operation_type = 'OUTBOUND'), 0) AS replay_base,
           COUNT(*) FILTER (WHERE d.base_quantity IS NULL) AS unbackfilled,
           COUNT(*) AS detail_count
    FROM stock_operation_details d
    JOIN stock_operations o ON o.id = d.operation_id
    WHERE o.status = 'CONFIRMED'
    GROUP BY d.material_id, d.lot_id
)
UPDATE stocks s
SET base_quantity         = r.replay_base,
    locked_base_quantity  = s.locked_quantity * spec.base_ratio,
    unit_model_status     = m.unit_model_status,
    last_updated          = NOW()
FROM materials m
JOIN material_unit_specs spec
  ON spec.material_id = m.id AND spec.is_default AND spec.status = 'ACTIVE'
JOIN replay r
  ON r.material_id = s.material_id
 AND r.lot_id IS NOT DISTINCT FROM s.lot_id
WHERE s.material_id = m.id
  AND m.unit_model_status = 'ACTIVE'
  AND r.unbackfilled = 0
  AND r.detail_count > 0
  AND r.replay_base >= 0
  AND r.replay_base = s.quantity * spec.base_ratio
  AND (m.base_quantity_scale > 0
       OR s.locked_quantity * spec.base_ratio = floor(s.locked_quantity * spec.base_ratio));

-- 对账失败 / 无法回放：记异常并阻断物资
INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'STOCK', s.id, s.material_id,
       'BALANCE_MISMATCH',
       '库存结存无法与可回放确认明细一致（历史拆零进位或缺失明细）', 'OPEN', NOW()
FROM stocks s
JOIN materials m ON m.id = s.material_id
WHERE m.unit_model_status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1
      FROM stock_operation_details d
      JOIN stock_operations o ON o.id = d.operation_id
      WHERE d.material_id = s.material_id
        AND d.lot_id IS NOT DISTINCT FROM s.lot_id
        AND o.status = 'CONFIRMED'
        AND d.base_quantity IS NOT NULL
  );

INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'STOCK', s.id, s.material_id,
       'BALANCE_MISMATCH',
       '库存结存与可回放明细的基础数量不一致，或基础锁定不符合精度', 'OPEN', NOW()
FROM stocks s
JOIN materials m ON m.id = s.material_id
WHERE m.unit_model_status = 'ACTIVE'
  AND s.base_quantity IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM inventory_unit_migration_issues i
      WHERE i.material_id = s.material_id
        AND i.object_type = 'STOCK'
        AND i.object_id = s.id
        AND i.reason_code = 'BALANCE_MISMATCH'
  );

UPDATE materials m
SET unit_model_status = 'MIGRATION_BLOCKED',
    updated_at        = NOW()
WHERE m.unit_model_status = 'ACTIVE'
  AND EXISTS (
      SELECT 1 FROM inventory_unit_migration_issues i
      WHERE i.material_id = m.id AND i.status = 'OPEN'
  );

-- 阻断物资的库存基础字段清空，保持只读历史
UPDATE stocks s
SET base_quantity = NULL,
    locked_base_quantity = NULL,
    unit_model_status = m.unit_model_status
FROM materials m
WHERE s.material_id = m.id
  AND m.unit_model_status = 'MIGRATION_BLOCKED';

-- =====================================================
-- 12. 迁移后一致性校验
--     ACTIVE 物资的确认明细与库存行必须已有基础数量快照
-- =====================================================

INSERT INTO inventory_unit_migration_issues
    (id, object_type, object_id, material_id, reason_code, detail, status, created_at)
SELECT pg_temp.crate_ulid(), 'STOCK_OPERATION_DETAIL', d.id, d.material_id,
       'UNSAFE_DETAIL', '确认明细缺少基础数量快照，不应出现在 ACTIVE 物资', 'OPEN', NOW()
FROM stock_operation_details d
JOIN stock_operations o ON o.id = d.operation_id
JOIN materials m ON m.id = d.material_id
WHERE m.unit_model_status = 'ACTIVE'
  AND o.status = 'CONFIRMED'
  AND d.base_quantity IS NULL;

-- 由于 12 可能发现新的异常，ACTIVE 物资最终收敛：任一 OPEN 异常即阻断
UPDATE materials m
SET unit_model_status = 'MIGRATION_BLOCKED',
    updated_at        = NOW()
WHERE m.unit_model_status = 'ACTIVE'
  AND EXISTS (
      SELECT 1 FROM inventory_unit_migration_issues i
      WHERE i.material_id = m.id AND i.status = 'OPEN'
  );

UPDATE stock_operation_details d
SET unit_spec_id = NULL, input_quantity = NULL, input_unit = NULL,
    conversion_ratio = NULL, base_quantity = NULL, base_unit = NULL,
    input_unit_cost = NULL, base_unit_cost = NULL
FROM materials m
WHERE d.material_id = m.id
  AND m.unit_model_status = 'MIGRATION_BLOCKED'
  AND d.base_quantity IS NULL;

UPDATE stocks s
SET base_quantity = NULL,
    locked_base_quantity = NULL,
    unit_model_status = m.unit_model_status
FROM materials m
WHERE s.material_id = m.id
  AND m.unit_model_status = 'MIGRATION_BLOCKED'
  AND s.base_quantity IS NULL;
