-- 养老入住终局：去世字段（离院收束复用既有字段，不新增）
ALTER TABLE healthcare.encounters ADD COLUMN death_date TIMESTAMPTZ;
ALTER TABLE healthcare.encounters ADD COLUMN death_cause TEXT;
