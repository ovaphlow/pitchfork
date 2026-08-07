-- 011 药房接方发药：发药单仓库列 + 医嘱关联查询索引
-- 历史发药单 warehouse 保留 NULL，不回填、不猜测；新建 ELDERLY_ROUTINE 发药单必须填写。
ALTER TABLE pharmacy.pharmacy_dispenses
    ADD COLUMN warehouse VARCHAR;

CREATE INDEX idx_pharmacy_dispense_items_order_item
    ON pharmacy.pharmacy_dispense_items (order_item_id)
    WHERE order_item_id IS NOT NULL;
