# NEXUS SHARED 系统宪法

本文件是项目的最高准则和唯一事实来源，适用于所有模块。
任何模块文档、代码实现，若与本宪法冲突，均以本宪法为准。

## 1. 通用语言

| 术语 | 定义 | 示例/说明 |
|-|-|-|
| Nexus	| 本系统的项目名称，代表四个基础模块的集合体 | 仓库名：nexus-backend |
| Settings | 用户配置模块，存储用户个性化的系统设置 | 时区、语言、通知偏好、主题 |
| Messages | 消息模块，存储用户之间的通信内容或系统通知 | 站内信、系统公告、提醒 |
| Files | 文件模块，存储用户上传文件的元数据，不包含文件实体 | 文件名、大小、MIME类型、存储路径、哈希值 |
| Interactions | 交互模块，存储用户与目标资源之间的行为关系 | 点赞（Like）、收藏（Favorite）、关注（Follow）、评分（Rate） |
| Target | Interactions 中被交互的资源对象 | 文章、视频、商品、评论、用户 |
| Shared Service | 作为底层基础能力的服务，为上层业务模块提供数据支撑 | 被其它服务调用 | 
| 读多写少 | 系统核心工作负载特征：读请求占比 > 90%，写入频率低 | 点赞状态的查询远多于点赞/取消点赞操作 |
| Base Module | 四大核心模块的统称 | Settings、Messages、Files、Interactions |

## 2. 接口契约

### 2.1 通用约束

- API 根路径
  - settings /crate-api/shared/v1/settings
  - messages /crate-api/shared/v1/messages
  - interactions /crate-api/shared/v1/interactions
  - files /crate-api/shared/v1/files

- HTTP方法
  - 严格遵循 RESTful 语义：GET（查询）、POST（创建）、PUT/PATCH（更新）、DELETE（删除）

- 请求格式
  - Content-Type: application/json（文件上传除外）

- 响应格式
  - JSON结构
  - 查询单一数据 {}
  - 查询多条数据 [{},{}]
  - 接口异常 RFC9457

- 错误码
  - HTTP 状态码

- 分页
  - 页 page
  - 行数 page_size

- 时间格式
  - RFC3339 2026-07-23T14:30:00Z

## 3. 架构与不可协商约束

所有模块必须遵守的强制性技术决策。

| 约束项 | 不可协商的规则 |
|-|-|
| 编程语言 | Rust 稳定版工具链 |
| Web框架 | Axum |
| 数据库 | SQLite，开启WAL模式 |
| 日志 | tracing + tracing-subscriber 结构化日志输出。终端输出原始日志，WARN、ERROR级别的日志以json格式写入jsonl文件中，每日轮转 |
| 配置管理 | 环境变量 + .env文件，通过dotenvy加载 |
| 错误处理 | 统一使用 anyhow（应用层）和 thiserror（库层），所有错误须包含上下文 |
| 模块解耦 | 四个模块在代码层面物理隔离，各模块 mod.rs 独立导出，禁止跨模块引用 |
| 部署形态 | 四个模块部署在同一进程中（单体部署），但代码逻辑完全解耦 |
| 并发模型 | 使用 tokio 多线程运行时（multi-thread），工作线程数 = CPU 核心数 |
| 数据库迁移 | 使用 sqlx migrate 管理 schema 变更，迁移脚本存放于 /migrations |

## 4. 全局非功能需求基线

以下基线适用于整个 Nexus 系统，所有模块必须达标。

|需求项|基线指标|测量/实现方式|
|-|-|-|
|读请求延迟（P99）|≤ 50ms（含缓存命中）|通过 tracing 记录耗时，日志聚合工具（如 ELK/Loki）提取|
|写请求延迟（P99）|≤ 200ms（不含文件上传）| 同上 |
|启动时间|<=2秒|从启动到健康检查通过|

## 5. 架构决策记录（索引）

## 6. 文档治理规则

### 6.1 文档存放路径

```text
```
```text
```
/                           # 项目根目录
├── CONSTITUTION.md         # 本宪法（不可移动）
├── README.md               # 项目简介 + 快速开始
├── Cargo.toml              # Rust 依赖定义
├── .env.example            # 环境变量示例（含 METRICS_ENABLED）
├── /migrations             # SQLite 迁移脚本
├── /src
│   ├── main.rs             # 启动入口
│   ├── /settings           # Settings 模块
│   ├── /messages           # Messages 模块
│   ├── /files              # Files 模块
│   ├── /interactions       # Interactions 模块
│   └── /shared             # 共享工具（数据库连接、中间件、日志、可观测性抽象）
├── /tests                  # 集成测试
├── /docs
│   ├── /adr                # 架构决策记录
│   └── /modules            # 各模块详细文档
└── /scripts                # 构建/部署脚本
```

宪法版本: v1.1
生效日期: 2026-07-23
