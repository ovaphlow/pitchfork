-- settings 表时间字段命名统一
ALTER TABLE settings RENAME COLUMN create_time TO created_at;
ALTER TABLE settings RENAME COLUMN update_time TO updated_at;
