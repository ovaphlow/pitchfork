# Pitchfork — 工作指引

## 快速导航

| 路径 | 作用 | 继续阅读 |
|---|---|---|
| `service-vertx-kotlin/` | Kotlin + Vert.x + jOOQ 后端（Trainova、Aceso） | `service-vertx-kotlin/AGENTS.md` |
| `ui-astro/` | Astro + React + Tailwind 前端 | `ui-astro/AGENTS.md` |
| `docs/` | 架构、规格和实施计划 | `docs/architecture.md` |
| `service-core-go-stdlib/` | Go 服务 | 不受本文档覆盖 |

进入子目录工作前，必须读取其适用的 `AGENTS.md`。完整产品边界、依赖和端口见 `docs/architecture.md`。

| 服务 | 端口 | 数据库 / 说明 |
|---|---:|---|
| Trainova API | `8421` | `ovaphlow`，`/crate-api/*` |
| Aceso API | `8422` | `aceso`，`/crate-api/*` |
| Aceso PostgreSQL | `5432` | Compose 容器 `pitchfork-aceso-db` |
| Aceso UI | `4324` | Astro 开发服务仅由用户管理 |

环境：JDK 25 toolchain、Node.js 20+、PostgreSQL 17、Podman 或 Docker。

## 代码发现与命令

- 代码定位和调用关系优先使用 `codebase-memory-mcp`：先索引，再依次使用 `search_graph`、`trace_path`、`get_code_snippet`；只有图谱不足、查找字符串字面量或非代码文件时才回退文本搜索。
- Bash 命令优先加 `rtk`，例如 `rtk git status`、`rtk rg`、`rtk read`、`rtk pnpm`；不要为普通代码理解使用未经必要验证的全库扫描。
- 保留用户已有的未提交改动；不得自行执行破坏性 Git 命令、重置工作区或删除宽泛目录。

## 共享 API 与数据约定

- 路由格式：`/crate-api/<module>/v1/<resource>`。
- 列表响应：`{ "records": [...], "meta": { "total": N } }`；空列表仍返回 `records: []` 与 `total: 0`。单条响应直接返回对象，错误响应为 `{ "error": "<message>" }`。
- 分页参数使用 `limit`、`offset`；ID 使用 26 位 Crockford Base32 ULID。
- 时间字段为 `created_at`、`updated_at`，类型为 `OffsetDateTime`；扩展 JSONB 字段命名为 `metadata`。
- 业务枚举遵循既有中文值；不得擅自引入英文 code 或改写已有 API code。
- 认证使用 JWT Bearer；前端必须通过产品范围的 `@pitchfork/shared/*` 客户端请求 API，以复用 token 注入、JSON 和 401 处理。

## Flyway 与模块边界

- 领域表及迁移归属其 lib：`libs/<module>/src/main/resources/db/migration/`。只有依赖该 lib 的 app 才会获得对应迁移。
- 迁移版本号按号段隔离：`V200+` inventories、`V300+` pharmacy、`V400+` nursing、`V500+` healthcare、`V600+` Trainova 预留；`apps/trainova` 的 `V5–V7` 是基线，不可移动或复制。
- 不用全局 Flyway `outOfOrder` 解决顺序问题；先核对现有历史和归属。新增 lib 按最大号段递增 100。
- 跨产品或跨领域改动必须保持最小范围，不把无关迁移、表、权限或后台任务并入小功能计划。

## 测试与数据库安全

- 默认验证只运行不访问数据库的单元测试、编译和前端构建；不要启动或管理 Astro 开发服务。
- 数据库集成/E2E 仅在用户明确要求，或已提供可恢复测试环境时执行。使用独立、可销毁的测试数据库（如 `aceso_test`），不得连接共享开发库、业务库或生产库。
- 集成测试必须自包含或清晰记录：Flyway 迁移、fixture 准备、执行命令、认证方式（不记录 token）、断言结果和清理步骤；不能把手工遗留数据或“构建成功”当作数据库验收。
- 后续优先维护单命令的测试入口来管理测试数据库生命周期；agent 只调用该入口，不自行猜测或反复操作 PostgreSQL。

## 协作角色

- 协作只按角色分工。调度者启动每个会话时传入实施计划路径和角色；角色职责不变。
- **计划**：编写和维护实施计划、范围、验收口径和角色矩阵；不以计划之名直接实现或宣告验收通过。
- **开发**：实现生产代码、Shared API 和 UI，编写并运行计划指定的单元和嵌入式路由测试，执行默认的不访问数据库验证；只修复经测试或评审报告的生产缺陷。
- **测试**：编写或维护计划指定的集成/E2E 测试及 fixture，在获授权的隔离环境运行真实数据库和浏览器验收；不得直接修改生产代码、Shared API、UI、单元测试或路由测试。
- **评审**：独立审查开发 diff、测试设计、验证证据和残余风险，确认计划口径、无副作用和修复闭环；不代替开发修生产代码，也不把构建通过当作最终验收。只有计划指定的评审可以记录最终验收结论。
- 旧称对照：计划者→计划、实现者→开发、独立验证者→测试、审查者→评审；历史记录或旧文档中的旧称仍指同一角色。
- 每份实施计划必须包含“角色、职责与交接”章节，以该章节为本任务的最终工作边界。该章节必须：列出参与角色；逐项分配实现、测试、执行与验收；列出各角色可修改的文件范围；指定测试命令、环境、数据库授权和数据清理要求；定义交付包、缺陷回传格式和最终状态更新者。未被计划分配的工作不得自行认领。
- 所有交接必须附修改文件、实际执行命令及结果。测试的失败报告必须提供前置数据、最小复现命令、预期结果、实际结果和脱敏证据；开发修复后必须重新交付候选版本，测试只复验受影响项，评审再作最终判断。
