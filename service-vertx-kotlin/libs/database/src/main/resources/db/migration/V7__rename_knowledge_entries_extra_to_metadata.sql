-- 统一知识条目扩展属性字段名
ALTER TABLE knowledge_entries RENAME COLUMN extra TO metadata;
