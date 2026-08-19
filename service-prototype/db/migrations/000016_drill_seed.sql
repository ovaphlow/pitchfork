-- 000016_drill_seed.sql
-- Built-in drill scenario seed (内置四大演练场景种子) of the
-- scenario-simulation-drill module. The migration writes the same four
-- drill scenarios, 21 steps and 15 assessment points that the drills
-- package seed function (Seed) inserts at startup, so a database-backed
-- deployment gets the built-in templates even without the in-memory
-- store.
--
-- Idempotency mirrors the seed function semantics (dedupe by scenario
-- name, skip whatever exists): every scenario insert guards on the name
-- (WHERE NOT EXISTS ... WHERE name = seed.name) with ON CONFLICT (id)
-- DO NOTHING as the re-run fallback (drill_scenarios.name has no UNIQUE
-- constraint); the step and point inserts run only when the fixed
-- scenario id actually exists (WHERE EXISTS, so a skipped scenario can
-- never orphan children into a missing foreign key) and only when the
-- (scenario_id, title) pair is still absent, again with ON CONFLICT
-- (id) DO NOTHING. The seed rows carry created_by='system', the
-- scenario status 启用 and an empty metadata object; created_at and
-- updated_at fall back to the now() column defaults.

INSERT INTO drill_scenarios (id, name, category, background, status, metadata, created_by)
SELECT seed.id, seed.name, seed.category, seed.background, seed.status, seed.metadata, seed.created_by
FROM (VALUES
    ('06FZWKXC9XYMF52K97P0CDBHK0', '大客流聚集应急演练', '大客流聚集', '新举办展览吸引大量观众，瞬时客流集中、入场通道拥堵。', '启用', '{}'::jsonb, 'system')
) AS seed(id, name, category, background, status, metadata, created_by)
WHERE NOT EXISTS (SELECT 1 FROM drill_scenarios AS existing WHERE existing.name = seed.name)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenarios (id, name, category, background, status, metadata, created_by)
SELECT seed.id, seed.name, seed.category, seed.background, seed.status, seed.metadata, seed.created_by
FROM (VALUES
    ('06FZWKXC9WREAGPKXQPXCWY604', '停电与基础设施故障应急演练', '停电与基础设施', '市电中断，安消防控制室、电梯、展厅、库房等关键区域面临停电风险。', '启用', '{}'::jsonb, 'system')
) AS seed(id, name, category, background, status, metadata, created_by)
WHERE NOT EXISTS (SELECT 1 FROM drill_scenarios AS existing WHERE existing.name = seed.name)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenarios (id, name, category, background, status, metadata, created_by)
SELECT seed.id, seed.name, seed.category, seed.background, seed.status, seed.metadata, seed.created_by
FROM (VALUES
    ('06FZWKXC9XCWPY4953NMC168FR', '火灾应急处置演练', '火灾', '展厅电气线路故障引发火情。', '启用', '{}'::jsonb, 'system')
) AS seed(id, name, category, background, status, metadata, created_by)
WHERE NOT EXISTS (SELECT 1 FROM drill_scenarios AS existing WHERE existing.name = seed.name)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenarios (id, name, category, background, status, metadata, created_by)
SELECT seed.id, seed.name, seed.category, seed.background, seed.status, seed.metadata, seed.created_by
FROM (VALUES
    ('06FZWKXC9Z2R3Q019A0PA8PMVM', '气象灾害应急演练', '气象灾害', '暑期高温、雷电、暴雨、台风等极端天气来袭。', '启用', '{}'::jsonb, 'system')
) AS seed(id, name, category, background, status, metadata, created_by)
WHERE NOT EXISTS (SELECT 1 FROM drill_scenarios AS existing WHERE existing.name = seed.name)
ON CONFLICT (id) DO NOTHING;

-- Steps of 大客流聚集应急演练 (sort_order 1..5)
INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9Z0ZPQ94A40PN29DZ0', '06FZWKXC9XYMF52K97P0CDBHK0', 1, '预警触发', '客流统计系统识别某区域人流密度超过阈值，系统自动预警', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9XTG6B9ZXC9089JFAC', '06FZWKXC9XYMF52K97P0CDBHK0', 2, '信息上报', '值班人员通过系统向指挥中心报告，同步启动“博物馆—主管部门—属地政府”信息联动机制', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WN5DEGVQBRHBP89SC', '06FZWKXC9XYMF52K97P0CDBHK0', 3, '预案启动', '启动大客流聚集专项应急疏解预案', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WNMZV5N4Y5ZKMQH78', '06FZWKXC9XYMF52K97P0CDBHK0', 4, '疏散引导', '利用视频监控和客流统计系统精准识别人流聚集区域，通过广播、电子屏、现场人员引导疏散', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9ZD702EWFA2K3ZNM5R', '06FZWKXC9XYMF52K97P0CDBHK0', 5, '限流分流', '实施预约限流、分时分批入场等措施', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Steps of 停电与基础设施故障应急演练 (sort_order 1..5)
INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WFC0SZQ17TPMPJ7HC', '06FZWKXC9WREAGPKXQPXCWY604', 1, '故障发现', '供配电系统监测到异常，系统自动报警', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9XGPCZ03R5YX2RYW3G', '06FZWKXC9WREAGPKXQPXCWY604', 2, '应急供电', '启动多层级供电保障体系，确保关键区域持续运行', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WRKWN3TZ2GC036EGR', '06FZWKXC9WREAGPKXQPXCWY604', 3, '观众疏导', '启动应急照明，通过广播引导观众有序撤离或原地等候', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WZXQAN0F0M4RH7HHW', '06FZWKXC9WREAGPKXQPXCWY604', 4, '空调故障应对', '启动备用通风方案，做好观众解释安抚工作', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WF3GNP5NDRMET57CC', '06FZWKXC9WREAGPKXQPXCWY604', 5, '设备抢修', '联系专业团队进行故障排查与修复', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Steps of 火灾应急处置演练 (sort_order 1..6)
INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9W4CTNGBVKG1F5Q6C4', '06FZWKXC9XCWPY4953NMC168FR', 1, '火情发现与报警', '烟感探测器触发、视频监控确认', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9Y5FNFH3M5N7WAXFYG', '06FZWKXC9XCWPY4953NMC168FR', 2, '初期处置', '使用灭火器、消火栓进行初期火灾扑救', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9ZBTW5ME86CC5JB81G', '06FZWKXC9XCWPY4953NMC168FR', 3, '人员疏散', '启动应急广播，按照疏散路线组织观众和工作人员撤离', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9ZVJ5PA4H2JK79GGJ4', '06FZWKXC9XCWPY4953NMC168FR', 4, '文物转移', '对展厅内珍贵文物实施紧急转移保护', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YES2F03EZDQHMJ8Y8', '06FZWKXC9XCWPY4953NMC168FR', 5, '消防联动', '拨打119，引导消防救援力量入场', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YPFVAFTJM5XBXER7G', '06FZWKXC9XCWPY4953NMC168FR', 6, '善后处置', '现场保护、损失评估、信息发布', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Steps of 气象灾害应急演练 (sort_order 1..5)
INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9ZDTAK9AD4SDE19TQW', '06FZWKXC9Z2R3Q019A0PA8PMVM', 1, '预警接收', '气象部门发布预警信息，系统自动接收并推送', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WR836QTS7GETRVN3G', '06FZWKXC9Z2R3Q019A0PA8PMVM', 2, '研判决策', '馆领导研判是否调整开放安排', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YMAEKDF95GVF1YC58', '06FZWKXC9Z2R3Q019A0PA8PMVM', 3, '信息发布', '通过官网、微信公众号、馆内广播等渠道发布调整通知', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9W9S2PAH4VDX9N067R', '06FZWKXC9Z2R3Q019A0PA8PMVM', 4, '现场处置', '加固户外设施、疏导滞留观众、做好防汛排涝', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_scenario_steps (id, scenario_id, sort_order, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.sort_order, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WF2YH136TS9N5ZTTW', '06FZWKXC9Z2R3Q019A0PA8PMVM', 5, '灾后恢复', '隐患排查、设施检修、恢复正常开放', 'system')
) AS seed(id, scenario_id, sort_order, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_scenario_steps AS step WHERE step.scenario_id = seed.scenario_id AND step.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Assessment points of 大客流聚集应急演练
INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WPZ286HB27R65EN0M', '06FZWKXC9XYMF52K97P0CDBHK0', '预警响应时间', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YV005TJC371ZD57XW', '06FZWKXC9XYMF52K97P0CDBHK0', '信息上报规范性', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WP6KGM057W3QVZJ5R', '06FZWKXC9XYMF52K97P0CDBHK0', '疏散路线合理性', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WX9V3HSQQNZAKK404', '06FZWKXC9XYMF52K97P0CDBHK0', '观众安抚效果', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Assessment points of 停电与基础设施故障应急演练
INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9ZGJ71S6Q642RBBTKG', '06FZWKXC9WREAGPKXQPXCWY604', '应急供电切换速度', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YDMTXE8ZF7CGCEA5G', '06FZWKXC9WREAGPKXQPXCWY604', '观众疏导秩序', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9Y63XNQXFFAT1GW67G', '06FZWKXC9WREAGPKXQPXCWY604', '信息发布及时性', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Assessment points of 火灾应急处置演练
INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WN4PXKA2HT2CT3CXR', '06FZWKXC9XCWPY4953NMC168FR', '报警及时性', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YES29W9Y5VT7270TW', '06FZWKXC9XCWPY4953NMC168FR', '初期处置规范性', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9Z1TZ4JHDGR5SA4NHG', '06FZWKXC9XCWPY4953NMC168FR', '疏散效率', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9XG5MHMCMM7GYX346G', '06FZWKXC9XCWPY4953NMC168FR', '文物安全保护', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

-- Assessment points of 气象灾害应急演练
INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9WZ1XD4BZ360MQ8F5C', '06FZWKXC9Z2R3Q019A0PA8PMVM', '预警响应速度', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9W9SB55MSEDMBFETX8', '06FZWKXC9Z2R3Q019A0PA8PMVM', '决策科学性', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YN7TG0NCWZPBAF794', '06FZWKXC9Z2R3Q019A0PA8PMVM', '信息发布覆盖面', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;

INSERT INTO drill_assessment_points (id, scenario_id, title, description, created_by)
SELECT seed.id, seed.scenario_id, seed.title, seed.description, seed.created_by
FROM (VALUES
    ('06FZWKXC9YWYXRT9FN169DPMF8', '06FZWKXC9Z2R3Q019A0PA8PMVM', '现场处置效果', '', 'system')
) AS seed(id, scenario_id, title, description, created_by)
WHERE EXISTS (SELECT 1 FROM drill_scenarios AS scenario WHERE scenario.id = seed.scenario_id)
  AND NOT EXISTS (SELECT 1 FROM drill_assessment_points AS point WHERE point.scenario_id = seed.scenario_id AND point.title = seed.title)
ON CONFLICT (id) DO NOTHING;
