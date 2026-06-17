# Build & Deploy — Gradle 构建与运行

## Build Commands

```bash
# 完整清理构建 (lib 源码变更后必须用此命令)
./gradlew clean :apps:service:installDist --no-build-cache --rerun-tasks

# 快速编译检查单个模块
./gradlew :libs:<module>:compileKotlin

# jOOQ codegen (schema 变更后)
./gradlew :libs:database:generateJooq
```

## Run

```bash
cd /path/to/service-vertx-kotlin
./apps/service/build/install/service/bin/service
```

## Kill Stale Processes

```bash
ps aux | grep '[s]ervice/bin/service' | awk '{print $2}' | xargs kill -9
```

## Logs

```bash
cat logs/app.jsonl | grep 'route error' | tail -5
```

## Gradle Cache

- Cache 位置: `.gradle-cache/` (通过 `GRADLE_USER_HOME` 环境变量设置)
- AliYun Maven 镜像已配置在 `~/.gradle/init.gradle` 中

## Critical: JAR Staleness

`installDist` 将 lib 的 JAR 复制到 distribution 目录。即使代码编译通过，distribution 中的 JAR **可能已过时**。

**Rule of thumb**: 修改任何 lib 模块的代码后，始终使用 `clean` 或 `--rerun-tasks` 重建。

## 多实例/端口冲突

两个实例运行会因端口冲突导致启动失败。每次重新启动前先杀光旧进程。
