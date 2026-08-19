-- 000023_evaluation_indicators.sql
-- Evaluation indicator dictionary (评估指标字典) of the comprehensive
-- evaluation module (module 4). The table carries the six-dimension ×
-- fifteen-indicator dictionary: dimension is one of
-- 响应速度/处置规范性/协同效率/观众安全/文物安全/舆情管控 (CHECK-guarded
-- like the other module enums), title is the required indicator name
-- (no default), weight defaults to 1 and must be at least 1, demo
-- separates the seven computable indicators (false) from the eight
-- presentation indicators (true), sort_order defaults to 0, description
-- and created_by default to empty strings. The id is a server-generated
-- 26-character Crockford Base32 ULID (full-repository convention);
-- timestamps follow the repository convention (created_at + updated_at,
-- now() defaults). The table has no metadata column (not applicable to
-- this module). Deletion of an indicator referenced by
-- evaluation_scores is rejected by the service layer through the
-- injected score-ref checker (the database keeps no FK here until the
-- evaluation_scores table lands in 000024; the service rule mirrors the
-- RESTRICT semantics).
--
-- The migration also writes the same fifteen built-in indicators that
-- the evaluation package seed function (Seed) inserts at startup, so a
-- database-backed deployment gets the built-in dictionary even without
-- the in-memory store. Idempotency mirrors the seed function semantics
-- (dedupe by title, skip whatever exists): every indicator insert
-- guards on the title (WHERE NOT EXISTS ... WHERE title = seed.title)
-- with ON CONFLICT (id) DO NOTHING as the re-run fallback
-- (evaluation_indicators.title has no UNIQUE constraint). The seed rows
-- carry created_by='system', the default weight 1, the per-dimension
-- sort_order 1..N and the demo flags of the specification; created_at
-- and updated_at fall back to the now() column defaults.

CREATE TABLE IF NOT EXISTS evaluation_indicators (
    id          TEXT PRIMARY KEY,
    dimension   TEXT NOT NULL CHECK (dimension IN ('响应速度', '处置规范性', '协同效率', '观众安全', '文物安全', '舆情管控')),
    title       TEXT NOT NULL,
    weight      INTEGER NOT NULL DEFAULT 1 CHECK (weight >= 1),
    demo        BOOLEAN NOT NULL DEFAULT false,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    description TEXT NOT NULL DEFAULT '',
    created_by  TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJTE84EP3234FBD2YD4', '响应速度', '预警响应速度', 1, false, 1, '从预警触发到应急响应的用时', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJR51ZC8236ENVBYE04', '响应速度', '指挥调度响应速度', 1, false, 2, '从指令下达到力量调集的用时', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJSSPP4A1WKASE61C90', '响应速度', '力量到场速度', 1, false, 3, '应急处置力量抵达现场的用时', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJTM9G3YMSW4VADTNVM', '处置规范性', '处置流程规范性', 1, false, 1, '处置步骤与应急预案流程的符合程度', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJR301CJGSBGAFWD0WC', '处置规范性', '信息报告规范性', 1, false, 2, '信息上报及时准确、要素完整', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJT70GCYFVZQK8B05SW', '协同效率', '部门协同效率', 1, false, 1, '跨部门联动配合的顺畅程度', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJT8YMF2JVH1G55CXCC', '协同效率', '信息共享效率', 1, false, 2, '现场信息传递与共享的时效', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJVPT0X4DZ5WYCW6G38', '观众安全', '观众疏散组织', 1, true, 1, '疏散组织有序、路线合理', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJTA3WKHB903X4VE548', '观众安全', '观众秩序维护', 1, true, 2, '现场秩序稳定、观众情绪安抚到位', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJV20R0H5WS4G2FN0GW', '观众安全', '观众伤亡防控', 1, true, 3, '无观众伤亡或伤情得到及时处置', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJTZ2WR0GWNKZATXR1R', '文物安全', '文物转移保护', 1, true, 1, '珍贵文物转移保护及时到位', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJRYWXD6XZRK3XGN678', '文物安全', '文物损失防控', 1, true, 2, '无文物损毁或损失可控', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJS44Q9521HC4CXTGX0', '舆情管控', '舆情监测预警', 1, true, 1, '舆情信息监测与预警及时', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJSBB3JJZ8TFR94XZ44', '舆情管控', '信息发布引导', 1, true, 2, '官方信息发布及时、口径统一', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO evaluation_indicators (id, dimension, title, weight, demo, sort_order, description, created_by)
SELECT seed.id, seed.dimension, seed.title, seed.weight, seed.demo, seed.sort_order, seed.description, seed.created_by
FROM (VALUES
    ('06G01KFRJR20W0Y8A16VB1CX98', '舆情管控', '舆情处置效果', 1, true, 3, '舆情发酵得到有效控制', 'system')
) AS seed(id, dimension, title, weight, demo, sort_order, description, created_by)
WHERE NOT EXISTS (SELECT 1 FROM evaluation_indicators AS existing WHERE existing.title = seed.title)
ON CONFLICT (id) DO NOTHING;
