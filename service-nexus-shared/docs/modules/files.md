# Files 模块

## 概述

Files 模块管理用户上传文件的元数据（文件名、大小、MIME 类型、存储路径、哈希值），不包含文件实体本身。

## 原实现状态 (service-vertx-kotlin)

> ⚠️ **注意**: 原 `service-vertx-kotlin/libs/files` 是一个**纯 stub 实现**，没有数据库表，没有持久化逻辑。
> 所有接口返回硬编码的模拟数据，仅用于验证路由结构。

### 原始代码

```kotlin
// FileRoutes.kt - 仅 stub
router.post("/upload").handler { ctx ->
    val file = ctx.fileUploads().iterator().next()
    ctx.json(JsonObject()
        .put("fileId", "file-${System.currentTimeMillis()}")
        .put("fileName", file.fileName())
        .put("size", file.size())
        .put("status", "uploaded"))
}

router.get("/:fileId").handler { ctx ->
    val fileId = ctx.pathParam("fileId")
    ctx.json(JsonObject()
        .put("fileId", fileId)
        .put("fileName", "example.txt")
        .put("size", 1024)
        .put("mimeType", "text/plain"))
}

router.delete("/:fileId").handler { ctx ->
    val fileId = ctx.pathParam("fileId")
    ctx.json(JsonObject().put("fileId", fileId).put("status", "deleted"))
}
```

## Nexus 表结构

根据 CONSTITUTION.md 中的 Files 定义，Nexus 使用以下表结构。

### 元数据表 (files)

| 列名 | 类型 | 约束 | 说明 |
| ------ | ------ | ------ | ------ |
| `id` | TEXT | PK, NOT NULL | ULID 主键 |
| `original_name` | TEXT | NOT NULL | 原始文件名 |
| `stored_name` | TEXT | NOT NULL | 服务端生成的 ULID 存储文件名 |
| `mime_type` | TEXT | NOT NULL | MIME 类型 |
| `size_bytes` | INTEGER | NOT NULL | 文件大小（字节） |
| `storage_path` | TEXT | NOT NULL | 存储路径（相对） |
| `hash_sha256` | TEXT | — | SHA-256 哈希 |
| `uploaded_by` | TEXT | — | 上传用户 ID |
| `created_at` | TEXT | NOT NULL DEFAULT (datetime('now')) | 创建时间 |

### 索引建议

| 索引名 | 列 | 说明 |
| -------- | ----- | ------ |
| `files_pkey` | `id` | 主键 |
| `idx_files_uploaded_by` | `uploaded_by` | 按上传用户查询 |
| `idx_files_hash` | `hash_sha256` | 按哈希去重 |

## API 路由映射

原 `service-vertx-kotlin` 路由前缀: `/crate-api/files/v1/*`

| HTTP 方法 | 路径 | 说明 | 状态 |
| ----------- | ------ | ------ | ------ |
| GET | `/health` | 健康检查 | stub |
| POST | `/upload` | 上传文件 | stub |
| GET | `/:fileId` | 获取文件元数据 | stub |
| DELETE | `/:fileId` | 删除文件 | stub |

## Nexus 迁移要点

根据 CONSTITUTION.md，Files 在 Nexus 中：

- **API 根路径**: `/crate-api/shared/v1/files`
- **数据库**: SQLite（WAL 模式）
- **技术栈**: Rust + Axum
- **迁移**: 使用 `sqlx migrate`，脚本放 `/migrations`
- **存储**: 文件实体保存到本地磁盘，元数据保存在 SQLite

### SQLite 建表

```sql
CREATE TABLE IF NOT EXISTS files (
    id            TEXT PRIMARY KEY,
    original_name TEXT NOT NULL,
    stored_name   TEXT NOT NULL,
    mime_type     TEXT NOT NULL,
    size_bytes    INTEGER NOT NULL,
    storage_path  TEXT NOT NULL,
    hash_sha256   TEXT,
    uploaded_by   TEXT,
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_files_uploaded_by ON files(uploaded_by);
CREATE INDEX IF NOT EXISTS idx_files_hash ON files(hash_sha256);
```

文件实体保存到由 `NEXUS_FILES_DIR` 配置的本地磁盘目录，`stored_name` 由服务端生成 ULID，绝不使用客户端文件名作为路径。删除接口会先物理删除磁盘文件，再删除数据库元数据；不使用软删除。

## Nexus HTTP API

Nexus 使用 `/crate-api/shared/v1/files` 作为根路径，所有请求均要求 IDP 的 `完整` 会话。

| HTTP 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 列表，支持 `uploaded_by`、`page`、`page_size` |
| POST | `/upload` | 上传 `multipart/form-data` 文件，字段名必须为 `file` |
| GET | `/:id` | 查询元数据 |
| GET | `/:id/content` | 下载文件实体 |
| DELETE | `/:id` | 物理删除文件与元数据 |

`uploaded_by` 从 IDP `subject_id` 获取。上传大小受 `NEXUS_MAX_UPLOAD_BYTES` 限制，服务返回内容 SHA-256 与安全生成的存储名。
