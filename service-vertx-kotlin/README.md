# service-vertx-kotlin

## 要求

- 构建工具: Gradle (Wrapper 8.14)
- 项目形式: Monorepo (`apps/` + `libs/`)
- 框架: Vert.x
- 数据库: PostgreSQL
- 数据库访问: jOOQ
- JDK: 最低 21
- 库版本: 尽量使用最新版
- Gradle 国内源: 阿里云 Maven 镜像，腾讯云 Gradle 发行版镜像
- Gradle 缓存和依赖: 保存在项目目录 `.gradle-cache/`（通过 `gradlew` 自动设置 `GRADLE_USER_HOME`）

## 模块

```
service-vertx-kotlin/
├── apps/
│   └── service/          # Vert.x 启动入口，挂载所有子路由
├── libs/
│   ├── auth/             # 认证 — /auth/*
│   ├── settings/         # 设置 — /settings/*
│   └── files/            # 文件 — /files/*
```

## 启动

```bash
./gradlew :apps:service:run
```
