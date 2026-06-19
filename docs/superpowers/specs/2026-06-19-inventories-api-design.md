# 库存管理模块 — 物资档案与批次接口设计

## 范围

仅实现 `materials`（物资档案）和 `lots`（批次）两张表的数据维护接口。
不涉及库存变动（入库/出库/调拨）、盘点、成本核算等功能。

## 模块结构

单一 Gradle module `libs:inventories`，内部按领域拆分为两组 Routes + Service。

```
libs/inventories/
├── build.gradle.kts
└── src/main/kotlin/com/ovaphlow/crate/inventories/
    ├── InventoriesRoutes.kt      # 顶层路由，挂载子路由
    ├── MaterialRoutes.kt         # /materials/* 端点
    ├── MaterialService.kt        # 物资数据库操作
    ├── LotRoutes.kt              # /lots/* 端点
    └── LotService.kt             # 批次数据库操作
```

## 路由注册

```
/inventories/v1/health           → InventoriesRoutes
/inventories/v1/materials/*      → MaterialRoutes
/inventories/v1/lots/*           → LotRoutes
```

## 物资档案 (materials)

### POST /inventories/v1/materials — 创建

|字段|类型|必填|说明|
|---|---|---|---|
|`code`|VARCHAR|是|物资编码，全局唯一，创建后不可修改|
|`name`|VARCHAR|是|物资名称|
|`category`|VARCHAR|是|分类路径，以 `/` 分隔层级|
|`spec`|VARCHAR|否|规格描述|
|`package_unit`|VARCHAR|是|包装单位|
|`split_unit`|VARCHAR|否|拆零单位，null 表示不支持拆零|
|`split_ratio`|NUMERIC|否|拆零换算比|
|`enable_batch_control`|BOOLEAN|否|默认 false|
|`cost_method`|VARCHAR|否|默认 'MOVING_AVG'，可选 'FIFO'|
|`metadata`|JSONB|否|扩展属性|
|`status`|VARCHAR|否|默认 'ACTIVE'，可选 'INACTIVE'/'DISCONTINUED'|

- 校验：`code` 唯一性、`status`/`cost_method` 枚举值合法性
- 返回：201 + 完整物资对象

### GET /inventories/v1/materials — 列表

查询参数：`code`、`name`(LIKE)、`category`、`status`、`enable_batch_control`、`limit`(50)、`offset`(0)
排序：`created_at DESC`
返回：`{ records: [...], meta: { total: N } }`

### GET /inventories/v1/materials/:id — 单条

不存在时 404。

### PUT /inventories/v1/materials/:id — 更新

部分更新，仅修改请求体中提供的字段。
排除 `code`（创建后不可修改）、`id`、`created_at`。
不存在时 404。
返回：更新后的完整物资对象。

### DELETE /inventories/v1/materials/:id — 物理删除

- 成功：204 No Content
- 被 `lots`/`stocks` 等外键引用时：409 Conflict，提示有依赖记录

## 批次 (lots)

### POST /inventories/v1/lots — 创建

|字段|类型|必填|说明|
|---|---|---|---|
|`material_id`|VARCHAR(32)|是|所属物资 ID|
|`batch_no`|VARCHAR|是|批号|
|`production_date`|DATE|否|生产日期|
|`expiry_date`|DATE|否|有效期至|
|`manufacturer`|VARCHAR|否|生产厂家|
|`supplier`|VARCHAR|否|供应商|
|`metadata`|JSONB|否|扩展属性|

幂等创建：相同 `(material_id, batch_no)` 已存在时，返回已有记录（200 而非 201）。
校验：`material_id` 对应物资必须存在且 `enable_batch_control = TRUE`。

### GET /inventories/v1/lots — 列表

查询参数：`material_id`、`batch_no`(LIKE)、`expiry_before`、`expiry_after`、`manufacturer`、`limit`(50)、`offset`(0)
排序：`expiry_date ASC NULLS LAST`（近效期优先）
返回：`{ records: [...], meta: { total: N } }`

### GET /inventories/v1/lots/:id — 单条

不存在时 404。

无 PUT，无 DELETE。

## 模块注册清单

1. 创建 `libs/inventories/build.gradle.kts`
2. `settings.gradle.kts` 添加 `"libs:inventories"`
3. `apps/service/build.gradle.kts` 添加 `implementation(project(":libs:inventories"))`
4. `Main.kt` 挂载路由：`apiRouter.route("/inventories/v1/*").subRouter(InventoriesRoutes.create(vertx, pool))`
5. 数据库迁移已在设计阶段完成（6 张表），当前范围仅用到 `materials` 和 `lots`
