# Adding a Module — 新增模块 Checklist

## Steps

### 1. 创建模块目录和构建文件

```
libs/<name>/
├── build.gradle.kts
└── src/main/kotlin/<name>/
    ├── <Name>Routes.kt
    └── <Name>Service.kt
```

标准 `build.gradle.kts` 模板：

```kotlin
plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    implementation(project(":libs:database"))
    implementation(project(":libs:common"))
    implementation(libs.vertx.web)
    implementation(libs.jooq)
}
```

### 2. 注册到 `settings.gradle.kts`

添加：
```kotlin
include("libs:<name>")
```

### 3. 添加到所属产品的入口模块依赖

先确认模块归属 Trainova、Aceso 或平台层；仅编辑该产品的 `apps/<product>/build.gradle.kts`：
```kotlin
implementation(project(":libs:<name>"))
```

### 4. 挂载路由到 Main.kt

```kotlin
apiRouter.route("/<module>/v1/*").subRouter(<Name>Routes.create(vertx, pool))
```

### 5. 如需要新建数据表

- 在 `libs/<name>/src/main/resources/db/migration/` 添加 Flyway 迁移脚本
- 选择该 lib 的预留版本号段；不要修改或移动 Trainova legacy `V5-V7`
- 执行 jOOQ codegen: `PITCHFORK_DB_PASSWORD=... ./gradlew :libs:<name>:generateJooq`
- 参见 [DB Migration](./db-migration.md)

### 6. 更新文档

- 更新本仓库 AGENTS.md 和 skills/
- 在 SKILLS.md 中补充新模块的 API 端点表
