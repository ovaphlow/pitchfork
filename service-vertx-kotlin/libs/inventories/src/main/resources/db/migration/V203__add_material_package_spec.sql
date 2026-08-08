-- =====================================================
-- 物资包装规格投影（整包/拆零显示与换算依据）
-- 库存账本仍以基础单位为唯一口径；包装规格仅用于展示层
-- 换算与录入换算：每包含 base_unit 的 package_size 个基础单位。
-- 可选：package_unit 与 package_size 必须同时为空或同时非空。
-- =====================================================
ALTER TABLE materials
    ADD COLUMN package_unit VARCHAR,
    ADD COLUMN package_size NUMERIC(20, 6);

ALTER TABLE materials
    ADD CONSTRAINT materials_package_check CHECK (
        (package_unit IS NULL AND package_size IS NULL)
        OR (package_unit IS NOT NULL AND package_size IS NOT NULL AND package_size > 0)
    );
