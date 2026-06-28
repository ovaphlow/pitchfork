-- 物资主表
CREATE TABLE IF NOT EXISTS materials
(
    id                   VARCHAR(32) PRIMARY KEY,
    code                 VARCHAR NOT NULL UNIQUE,
    name                 VARCHAR NOT NULL,
    category             VARCHAR NOT NULL,
    spec                 VARCHAR,
    package_unit         VARCHAR NOT NULL,
    split_unit           VARCHAR,
    split_ratio          NUMERIC(10, 4),
    enable_batch_control BOOLEAN     DEFAULT FALSE,
    cost_method          VARCHAR     DEFAULT 'MOVING_AVG'
        CHECK (cost_method IN ('MOVING_AVG', 'FIFO')),
    metadata             JSONB,
    status               VARCHAR     DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    created_at           TIMESTAMPTZ DEFAULT NOW()
);

-- 批次表
CREATE TABLE IF NOT EXISTS lots
(
    id              VARCHAR(32) PRIMARY KEY,
    material_id     VARCHAR(32) NOT NULL REFERENCES materials(id),
    batch_no        VARCHAR     NOT NULL,
    production_date DATE,
    expiry_date     DATE,
    manufacturer    VARCHAR,
    supplier        VARCHAR,
    metadata        JSONB,
    UNIQUE (material_id, batch_no)
);

-- 批次查询索引
CREATE INDEX IF NOT EXISTS idx_lots_material_id ON lots (material_id);
CREATE INDEX IF NOT EXISTS idx_lots_batch_no ON lots (batch_no);
