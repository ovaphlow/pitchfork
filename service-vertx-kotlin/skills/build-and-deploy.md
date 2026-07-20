# Build & Deploy — Gradle 构建与运行

## Product Commands

```bash
# Trainova distribution
./gradlew clean :apps:trainova:installDist --no-build-cache --rerun-tasks

# Aceso distribution
./gradlew clean :apps:aceso:installDist --no-build-cache --rerun-tasks

# 快速编译检查单个模块
./gradlew :libs:<module>:compileKotlin

# jOOQ codegen (schema 变更后；密码仅从环境变量读取)
PITCHFORK_DB_PASSWORD='local-development-password' ./gradlew :libs:<module>:generateJooq
```

## Run

每个进程都必须显式指定本地配置文件和两个 secret：

```bash
cp apps/trainova/config.example.json apps/trainova/config.json
export PITCHFORK_CONFIG="$PWD/apps/trainova/config.json"
export PITCHFORK_DB_PASSWORD='local-development-password'
export PITCHFORK_JWT_SECRET='long-random-secret'
./gradlew :apps:trainova:run
```

Trainova 使用端口 `8421`；Aceso 使用 `8422`。配置文件不含密码，`config.json` 和 `.env` 均不得提交。

## Gradle Cache

- Cache 位置: `.gradle-cache/` (通过 `GRADLE_USER_HOME` 环境变量设置)
- AliYun Maven 镜像已配置在 `~/.gradle/init.gradle` 中

## Critical: JAR Staleness

`installDist` 将 lib 的 JAR 复制到 distribution 目录。即使代码编译通过，distribution 中的 JAR **可能已过时**。

修改任何 lib 模块代码后，使用 `clean` 或 `--rerun-tasks` 重新构建目标产品的 distribution。
