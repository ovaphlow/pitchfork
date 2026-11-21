#!/usr/bin/env bash
#
# aceso-test.sh —— 一条命令跑完 Aceso / Healthcare 的数据库集成测试。
#
# 用法：
#   cd service-vertx-kotlin && ./scripts/aceso-test.sh
#
# 它会自动完成：取密码 → 找到或拉起 PostgreSQL →（库不存在才）建库 →
# 一次 gradle 调用跑 :libs:healthcare:test 与 :apps:aceso:test → 收摊（默认停掉自己起的容器）。
#
# 生命周期（2026-09-29 仓库策略，见根 AGENTS.md「服务与资源生命周期」）：
#   - 开发服务（Aceso API 8422 / UI 4324 / Identity 8420 / Nexus 8421 / 开发库 5432）
#     只由用户启停；本脚本不会启动、停止、杀进程或重绑这些服务。
#   - 测试库容器（pitchfork-aceso-test-db，端口 55432）属于「测试资源」，可以由
#     agent/脚本用 -d 后台启动；但本脚本在退出时（含失败与 Ctrl+C）必定 down 掉
#     自己启动的那个，并打印 `podman ps` 证据确认已消失，绝不跨会话留存。
#   - `--port 5432 --database <库名>` 借用用户已启动的实例；脚本不删库、不停它。
#   - `--keep` 会显式警告容器仍在运行并给出收尾命令，不静默遗留。
#   - 收摊如实：`down` 失败 / 容器仍在 / 端口仍监听时，只打印「收摊未成功」+ 残留资源
#     + 人工清理命令，绝不打印成功措辞；收摊未成功而测试本身通过时，脚本以 exit 3 结束
#     （宁可让调用方看见非 0，也不给一个假的 OK）。
#
# 项目隔离（2026-09-29 险情修复，必须有，详见 apps/aceso/compose.test.yaml 顶部注释）：
#   - 险情：compose.test.yaml 与开发库 compose.yaml 同在 apps/aceso。podman-compose 的
#     项目名优先级是 -p/--project-name > COMPOSE_PROJECT_NAME > 文件顶层 name: > 文件
#     所在目录名；没有 name: 时测试 compose 的项目名退回目录名 'aceso'，与开发库
#     （pitchfork-aceso-db，0.0.0.0:5432）同名 —— 于是测试的 up/down 操作的是同一个
#     pod（pod_aceso）与同一个网络（aceso_default）。上次收摊因此试图删掉用户开发库
#     所在的 pod 与网络，只因开发容器正在运行（不加 -f 删不掉）才侥幸没造成损坏。
#   - 三层隔离指向同一个项目名：compose.test.yaml 顶层 name: pitchfork-aceso-test、
#     每次 compose 调用显式带 -p pitchfork-aceso-test、并导出 COMPOSE_PROJECT_NAME。
#   - down 硬护栏：执行任何 down 之前先断言「目标项目名 == pitchfork-aceso-test」，
#     不等就拒绝执行并报错退出；脚本从不使用 -f/--force，也不使用 remove-orphans。
#   - gradlew 可执行位：git index 里 gradlew 登记为 100644 且本仓库 core.fileMode=false，
#     新克隆/worktree 里执行 ./gradlew 会报「env: ./gradlew: 权限不够」；脚本在调用
#     gradle 之前检测并（必要时）chmod +x，且把这件事打印出来，不静默、不掩盖真实错误。
#
# 安全性：
#   - 库名必须显式给出（一次性容器除外）：--database，或环境变量 ACESO_DB_NAME。
#   - 绝不 DROP DATABASE；已有库只增量迁移。
#   - 密码只从环境变量或 apps/aceso/.env 读取，绝不回显到 stdout / 日志。
#   - 密码通过环境变量传给 Gradle，不进入命令行参数。
#
# 旧的 scripts/aceso-integration-tests.sh（DROP/CREATE 窄口径入口）已于 2026-11-21 删除：
# 它依赖「每次重置库」的模型，与本仓库现行口径（不删库、库名显式给出）冲突。
# 唯一入口就是本脚本。详见 docs/aceso-test-database.md。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$BACKEND_DIR/.." && pwd)"
COMPOSE_FILE="$BACKEND_DIR/apps/aceso/compose.test.yaml"
ENV_FILE="$BACKEND_DIR/apps/aceso/.env"

# ---- 常量 ---------------------------------------------------------------

DB_NAME="${ACESO_DB_NAME:-}"   # 空 = 待定：一次性容器用容器自带库，否则必须 --database
DB_USER="${PITCHFORK_TEST_DB_USER:-ovaphlow}"
DB_HOST="127.0.0.1"

TEST_PORT=55432                # compose.test.yaml 映射的测试库端口
TEST_CONTAINER="pitchfork-aceso-test-db"
DEV_PORT=5432                  # compose.yaml 开发库端口
DEV_CONTAINER="pitchfork-aceso-db"

# 隔离项目名 —— 隔离的第三层（另两层：compose.test.yaml 顶层 name:、每次调用的 -p）。
# podman-compose 1.6.0 的 PodmanCompose._parse_compose_file 里的优先级是：
#   -p/--project-name  >  $COMPOSE_PROJECT_NAME  >  文件顶层 name:  >  文件所在目录名
# 目录名会是 'aceso'（与开发库同项目），所以这里导出环境变量再兜一层。
ISOLATED_PROJECT="pitchfork-aceso-test"
export COMPOSE_PROJECT_NAME="$ISOLATED_PROJECT"

READY_TIMEOUT="${ACESO_READY_TIMEOUT:-60}"   # 等待容器就绪的秒数

GRADLE_BASE_ARGS=(--no-daemon -Pkotlin.compiler.execution.strategy=in-process)

# ---- 运行时状态 ---------------------------------------------------------

PORT=""                 # 目标端口，探测或 --port 决定
CONTAINER=""            # 用哪个容器里的 psql 重置库
CONTAINER_CHOSEN_BY_USER=0
KEEP=0
NO_MIGRATE="${ACESO_NO_MIGRATE:-0}"
OFFLINE=0
PASSTHROUGH_ARGS=()
STARTED_CONTAINER=0
RUNTIME=""              # podman / docker，未探测到时为空
COMPOSE_ARR=()          # compose 命令（podman compose / podman-compose / docker compose ...）
COMPOSE_CMD=()          # COMPOSE_ARR + -p 隔离项目名 + -f compose.test.yaml：唯一的 compose 调用形态
PASSWORD=""
PASSWORD_SOURCE=""
RESIDUAL_FOUND=0        # 收摊证据里是否发现隔离项目名下的残留资源

# ---- 输出辅助 -----------------------------------------------------------

info()  { printf '[aceso-test] %s\n' "$*"; }
warn()  { printf '[aceso-test] 警告：%s\n' "$*" >&2; }
fail()  { printf '[aceso-test] 错误：%s\n' "$*" >&2; }

usage() {
    cat <<'USAGE'
aceso-test.sh —— 一条命令跑 Aceso / Healthcare 数据库集成测试

用法：
  ./scripts/aceso-test.sh [选项] [-- <额外的 gradlew 参数>]

选项：
  --database <name>      目标库名。**不给就不猜**：
                           - 由本脚本自己启动一次性容器时，缺省用容器自带的 aceso_test；
                           - 指向既有实例（--port 5432 / --container ...）时必须显式给出。
  --port <55432|5432>    目标端口，跳过自动探测（55432 = 本脚本的一次性容器，5432 = 既有实例）。
  --container <name>     指定执行 psql / pg_isready 的容器名（覆盖上面的默认值）。
  --no-migrate           不执行 Flyway（-Dintegration.db.migrate=false）：只复用既有 schema。
                         指向既有库、又不想让测试推进迁移时用它。
  --keep                 跑完保留容器，方便下次更快（下次若端口已通会自动复用）。
                         注意：保留的容器必须由你在本轮结束前收掉，不得跨会话存活。
  --offline              给 gradlew 加 --offline。
  -h, --help             显示本帮助。

数据库生命周期（2026-11-21 起）：
  测试与脚本**都不再 DROP / CREATE 数据库**。目标库只在「不存在」时被 CREATE 一次，
  已有内容一律保留；数据隔离靠测试自身的 fixture 前缀 + 清理 + 残差断言。
  因此同一台实例上的任意库都能跑：一次性 tmpfs 容器、本机开发库、CI 的服务库。
  ⚠️ 指向既有库时默认仍会执行 Flyway migrate（幂等，但会把「待应用迁移」推到该库）；
     --no-migrate 用于只读 schema、不动迁移。

Flyway 冲突（已解决，不再是执行顺序约束）：
  DatabaseConfig.migrate 配了 baselineOnMigrate + ignoreMigrationPatterns("*:missing","*:ignored")
  + outOfOrder(true)，各模块号段（V2xx/V3xx/V4xx/V5xx/V6xx）共享一个 schema history 也能共存。
  历史上要求的「先 :libs:healthcare:test、再 :apps:aceso:test」因此不再必要；
  本脚本改为一次 gradle 调用同时跑两个模块。

容器项目隔离（2026-09-29 险情修复，保持不变）：
  测试 compose 固定在项目 pitchfork-aceso-test（compose.test.yaml 顶层 name:，
  每次调用还显式带 -p pitchfork-aceso-test，另有 COMPOSE_PROJECT_NAME 兜底）。
  脚本在执行任何 down 之前会断言项目名等于 pitchfork-aceso-test，不等就拒绝执行并报错退出；
  绝不使用 -f/--force，也不使用 remove-orphans。

密码来源（按顺序，取到即用，绝不回显）：
  1) 环境变量 PITCHFORK_DB_PASSWORD
  2) 环境变量 PITCHFORK_TEST_DB_PASSWORD
  3) apps/aceso/.env 里的 PITCHFORK_DB_PASSWORD

示例：
  # 最常用：用脚本自己起的一次性容器 + 容器自带库 aceso_test
  ./scripts/aceso-test.sh

  # 直接对已有实例跑（库名必须给），且不推进迁移
  ./scripts/aceso-test.sh --port 5432 --database aceso --no-migrate

  # 保持容器不销毁
  ./scripts/aceso-test.sh --keep
USAGE
}

# ---- 参数解析（必须在取密码之前，--help 不需要密码） --------------------

while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)
            [[ $# -ge 2 ]] || { fail "--port 需要一个参数（55432 或 5432）"; exit 2; }
            PORT="$2"
            CONTAINER_CHOSEN_BY_USER=0
            shift 2
            ;;
        --container)
            [[ $# -ge 2 ]] || { fail "--container 需要一个容器名"; exit 2; }
            CONTAINER="$2"
            CONTAINER_CHOSEN_BY_USER=1
            shift 2
            ;;
        --keep)
            KEEP=1
            shift
            ;;
        --database)
            [[ $# -ge 2 ]] || { fail "--database 需要一个库名"; exit 2; }
            DB_NAME="$2"
            shift 2
            ;;
        --no-migrate)
            NO_MIGRATE=1
            shift
            ;;
        --offline)
            OFFLINE=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            PASSTHROUGH_ARGS=("$@")
            break
            ;;
        *)
            fail "未知参数：$1"
            echo
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -n "$PORT" && "$PORT" != "55432" && "$PORT" != "5432" ]]; then
    fail "--port 只接受 55432 或 5432，收到 '$PORT'。"
    echo "  55432 = 测试专用容器；5432 = 开发库端口所在容器（仍只操作 aceso_test）。" >&2
    exit 2
fi

# ---- 护栏：不猜库名、不删库 ---------------------------------------------
# 2026-11-21 之前：库名硬编码 aceso_test，且非 aceso_test 一律拒绝。
# 现在：库名由 --database / ACESO_DB_NAME 决定；缺省只在「本脚本自己启动一次性容器」时
# 取容器自带库。指向既有实例却不给库名时直接报错，避免猜错库。

if [[ -n "$DB_NAME" ]]; then
    case "$DB_NAME" in
        *[!A-Za-z0-9_]*)
            fail "库名只允许字母数字下划线：'$DB_NAME'"
            exit 2
            ;;
    esac
fi

# ---- 1) 取密码 ----------------------------------------------------------

load_password_from_env_file() {
    [[ -f "$ENV_FILE" ]] || return 0
    (
        set -a
        # shellcheck disable=SC1090,SC1091
        source "$ENV_FILE" >/dev/null 2>&1 || true
        set +a
        printf '%s' "${PITCHFORK_DB_PASSWORD:-${PITCHFORK_TEST_DB_PASSWORD:-}}"
    )
}

if [[ -n "${PITCHFORK_DB_PASSWORD:-}" ]]; then
    PASSWORD="$PITCHFORK_DB_PASSWORD"
    PASSWORD_SOURCE="环境变量 PITCHFORK_DB_PASSWORD"
elif [[ -n "${PITCHFORK_TEST_DB_PASSWORD:-}" ]]; then
    PASSWORD="$PITCHFORK_TEST_DB_PASSWORD"
    PASSWORD_SOURCE="环境变量 PITCHFORK_TEST_DB_PASSWORD"
else
    PASSWORD="$(load_password_from_env_file)"
    [[ -n "$PASSWORD" ]] && PASSWORD_SOURCE="$ENV_FILE"
fi

if [[ -z "$PASSWORD" ]]; then
    fail "缺少数据库密码，无法继续（脚本不会猜密码）。"
    cat >&2 <<EOF

请任选一种方式提供密码：

  方式一（临时，命令行）：
      PITCHFORK_DB_PASSWORD=<你的测试库密码> ./scripts/aceso-test.sh

  方式二（推荐，一次性写进文件，之后不用再 export）：
      编辑 $ENV_FILE
      填入这一行（可参考同目录 .env.example）：
          export PITCHFORK_DB_PASSWORD=<你的测试库密码>

  方式三：export PITCHFORK_TEST_DB_PASSWORD=<你的测试库密码>

测试库是独立、可销毁的 aceso_test，请勿填业务库/生产库密码。
EOF
    exit 2
fi

info "密码来源：$PASSWORD_SOURCE（内容不显示）"

# ---- 项目隔离护栏（任何 down 之前必须通过） ------------------------------

# 即将执行的 compose 调用是否显式带了 -p 隔离项目名。
compose_argv_has_project() {
    [[ " ${COMPOSE_CMD[*]:-} " == *" -p $ISOLATED_PROJECT "* ]]
}

# compose 文件顶层的 name:（没有则输出空）。
compose_file_project_name() {
    [[ -f "$COMPOSE_FILE" ]] || return 0
    sed -n 's/^name:[[:space:]]*//p' "$COMPOSE_FILE" | head -n 1 | tr -d "\"'" | sed 's/[[:space:]]*$//'
}

# 让 compose 自己把项目名解析出来（实测：podman compose → 外部 provider podman-compose，
# `config` 的 stdout 首行就是 `name: <生效项目名>`；docker compose 同样会打印 name:）。
# 失败/无输出都视为「无法从运行时确认」，退回 argv 与文件 name: 的校验。
compose_runtime_project_name() {
    local out
    out="$(timeout 30 "${COMPOSE_CMD[@]}" config 2>/dev/null)" || return 1
    printf '%s\n' "$out" \
        | sed -e 's/\x1b\[[0-9;]*[a-zA-Z]//g' -e 's/\r$//' \
        | sed -n 's/^[[:space:]]*name:[[:space:]]*//p' | head -n 1 | tr -d "\"'"
}

# 容器上的 compose 项目标签（podman-compose 1.6.0 同时写 com.docker.compose.project 与
# io.podman.compose.project；见 podman_compose.py 里 pod 与容器创建处）。
container_compose_project() {
    local c="$1" v=""
    [[ -n "$RUNTIME" ]] || return 0
    v="$("$RUNTIME" inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$c" 2>/dev/null || true)"
    if [[ -z "$v" || "$v" == "<no value>" ]]; then
        v="$("$RUNTIME" inspect --format '{{index .Config.Labels "io.podman.compose.project"}}' "$c" 2>/dev/null || true)"
    fi
    [[ "$v" == "<no value>" ]] && v=""
    printf '%s' "$v"
}

# down 硬护栏：确认「即将被 down 掉的项目」就是隔离项目。任何一项不满足都拒绝执行 down。
# 判据（逐条累计，任一不成立即拒绝）：
#   1) 即将执行的 argv 显式带 -p pitchfork-aceso-test；
#   2) compose.test.yaml 顶层 name:（若有）等于隔离项目名；
#   3) 目标容器上的 compose 项目标签（若有）等于隔离项目名；
#   4) compose 自己解析出的项目名（若能拿到）等于隔离项目名。
assert_isolated_project_or_refuse() {
    local reason="" file_name="" ctr_proj="" rt_name="" cfg_rc=0

    if ! compose_argv_has_project; then
        reason="即将执行的 compose 调用没有显式带 -p $ISOLATED_PROJECT（argv：${COMPOSE_CMD[*]:-<空>}）"
    fi

    if [[ -z "$reason" ]]; then
        file_name="$(compose_file_project_name)"
        if [[ -n "$file_name" && "$file_name" != "$ISOLATED_PROJECT" ]]; then
            reason="$COMPOSE_FILE 顶层 name: 是 '$file_name'，不是 '$ISOLATED_PROJECT'"
        elif [[ -z "$file_name" ]]; then
            warn "$COMPOSE_FILE 顶层没有 name:（可能被误删）；本次靠 -p 与 COMPOSE_PROJECT_NAME 兜底为 '$ISOLATED_PROJECT'。"
        fi
    fi

    if [[ -z "$reason" && -n "$RUNTIME" ]] && "$RUNTIME" container exists "$CONTAINER" >/dev/null 2>&1; then
        ctr_proj="$(container_compose_project "$CONTAINER")"
        if [[ -n "$ctr_proj" && "$ctr_proj" != "$ISOLATED_PROJECT" ]]; then
            reason="容器 $CONTAINER 的 compose 项目标签是 '$ctr_proj'，不是 '$ISOLATED_PROJECT'（正是上次险情的形态）"
        elif [[ -z "$ctr_proj" ]]; then
            warn "容器 $CONTAINER 没有 compose 项目标签，无法用它确认项目名；改以 argv 与文件 name: 为准。"
        fi
    fi

    if [[ -z "$reason" && -n "${COMPOSE_CMD[*]:-}" ]]; then
        rt_name="$(compose_runtime_project_name)" || cfg_rc=$?
        if [[ "$cfg_rc" == "0" && -n "$rt_name" && "$rt_name" != "$ISOLATED_PROJECT" ]]; then
            reason="compose 自己解析出的项目名是 '$rt_name'，不是 '$ISOLATED_PROJECT'"
        elif [[ "$cfg_rc" != "0" ]]; then
            warn "无法从运行时确认项目名（compose config 失败）；已用 argv 的 -p 与文件 name: 校验通过。"
        fi
    fi

    if [[ -z "$reason" ]]; then
        return 0
    fi

    fail "拒绝执行 compose down：$reason"
    cat >&2 <<EOF
本脚本只允许 down 隔离项目 '$ISOLATED_PROJECT'（容器 $TEST_CONTAINER，端口 $TEST_PORT）。
危险所在：apps/aceso/compose.yaml（开发库 $DEV_CONTAINER，0.0.0.0:$DEV_PORT）与本测试
compose 文件同在 apps/aceso。项目名一旦退回目录名 'aceso'，两者就是同一个项目：同一个
pod（pod_aceso）、同一个网络（aceso_default），down 会删掉用户开发库所在的 pod 与网络
（2026-09-29 险情：只因当时开发容器正在运行、不加 -f 删不掉，才侥幸没有损坏）。
因此：项目名不对就拒绝 down；也绝不使用 -f/--force 或 remove-orphans。

没有执行任何 down。人工确认后如需清理，请显式带隔离项目名（不加 -f）：
  cd $BACKEND_DIR && ${COMPOSE_ARR[*]:-podman compose} -p $ISOLATED_PROJECT -f $COMPOSE_FILE down
并确认开发库仍在（只读检查，不要对它做任何操作）：
  ${RUNTIME:-podman} ps -a --format '{{.Names}}\t{{.Status}}' | grep -F '$DEV_CONTAINER'
EOF
    return 1
}

# ---- 收摊（函数定义必须早于 trap 安装，否则提前退出的分支会找不到函数） ----

# 只列隔离项目名下的残留容器/pod/网络：证据必须限定在「本脚本该收掉的东西」上，
# 绝不把开发库写成待清理对象。RESIDUAL_FOUND=1 表示仍有残留。
show_isolated_residuals() {
    RESIDUAL_FOUND=0
    if [[ -z "$RUNTIME" ]]; then
        info "  （未检测到容器运行时，无法列出；请人工执行：podman ps -a | grep -F $ISOLATED_PROJECT）"
        return 0
    fi

    local containers pods networks
    containers="$("$RUNTIME" ps -a --format '{{.Names}}\t{{.Status}}' 2>/dev/null | grep -F "$ISOLATED_PROJECT" || true)"
    pods="$("$RUNTIME" pod ps --format '{{.Name}}\t{{.Status}}' 2>/dev/null | grep -F "$ISOLATED_PROJECT" || true)"
    networks="$("$RUNTIME" network ls --format '{{.Name}}' 2>/dev/null | grep -F "$ISOLATED_PROJECT" || true)"

    if [[ -n "$containers" ]]; then
        RESIDUAL_FOUND=1
        info "  残留容器（$RUNTIME ps -a | grep -F $ISOLATED_PROJECT）："
        while IFS= read -r line; do info "    $line"; done <<<"$containers"
    else
        info "  容器：无（$RUNTIME ps -a 中没有 $ISOLATED_PROJECT 名下的容器）"
    fi
    if [[ -n "$pods" ]]; then
        RESIDUAL_FOUND=1
        info "  残留 pod（$RUNTIME pod ps | grep -F $ISOLATED_PROJECT）："
        while IFS= read -r line; do info "    $line"; done <<<"$pods"
    else
        info "  pod：无（$RUNTIME pod ps 中没有 pod_$ISOLATED_PROJECT）"
    fi
    if [[ -n "$networks" ]]; then
        RESIDUAL_FOUND=1
        info "  残留网络（$RUNTIME network ls | grep -F $ISOLATED_PROJECT）："
        while IFS= read -r line; do info "    $line"; done <<<"$networks"
    else
        info "  网络：无（$RUNTIME network ls 中没有 ${ISOLATED_PROJECT}_default）"
    fi
}

port_still_open() {
    timeout 3 bash -c "exec 3<>/dev/tcp/$DB_HOST/$1" >/dev/null 2>&1
}

cleanup() {
    local code=$?
    trap - EXIT

    if [[ "$STARTED_CONTAINER" == "1" && "$KEEP" == "1" ]]; then
        warn "--keep 生效：容器 $CONTAINER 仍在运行，本脚本不会收掉它；请在离开本轮会话前执行下面的收尾命令。"
        info "  本轮结束前必须自己收掉，命令已带隔离项目名（不加 -f/--force）："
        info "    cd $BACKEND_DIR && ${COMPOSE_ARR[*]:-podman compose} -p $ISOLATED_PROJECT -f $COMPOSE_FILE down"
        info "  确认已消失：${RUNTIME:-podman} ps -a --format '{{.Names}}' | grep -F $ISOLATED_PROJECT"
        exit "$code"
    fi

    if [[ "$STARTED_CONTAINER" != "1" ]]; then
        info "收摊：测试库容器不是本脚本启动的，保持原状（不主动停止别人的/用户管理的服务）。"
        info "  如需停止它（请自行确认它确实属于测试项目 $ISOLATED_PROJECT；不加 -f/--force）："
        info "    cd $BACKEND_DIR && ${COMPOSE_ARR[*]:-podman compose} -p $ISOLATED_PROJECT -f $COMPOSE_FILE down"
        exit "$code"
    fi

    info "收摊：down 本脚本启动的隔离项目（项目 $ISOLATED_PROJECT，容器 $CONTAINER，端口 $PORT）..."
    info "  命令：${COMPOSE_CMD[*]:-<未探测到 compose>} down"

    local -a reasons=()
    local down_rc=0 down_out="" container_exists_after=0 port_after=0

    if [[ -z "${COMPOSE_CMD[*]:-}" ]]; then
        reasons+=("没有可用的 compose 命令（podman/docker 都没探测到），无法执行 down")
    elif ! assert_isolated_project_or_refuse; then
        reasons+=("护栏拒绝执行 down：项目名不是隔离项目 $ISOLATED_PROJECT（原因见上）")
    else
        down_out="$(timeout 120 "${COMPOSE_CMD[@]}" down 2>&1)" || down_rc=$?
        if [[ -n "$down_out" ]]; then
            info "  compose down 原始输出（不加工）："
            while IFS= read -r line; do info "    compose| $line"; done <<<"$down_out"
        fi
        if [[ "$down_rc" != "0" ]]; then
            reasons+=("compose down 返回非 0（exit $down_rc）；上面是它的原始输出")
        fi
    fi

    if [[ -n "$RUNTIME" ]] && "$RUNTIME" container exists "$CONTAINER" >/dev/null 2>&1; then
        container_exists_after=1
        reasons+=("容器 $CONTAINER 仍然存在（$RUNTIME ps -a 里还能看到）")
    fi
    port_still_open "$PORT" && port_after=1

    info "收摊证据（只列隔离项目 $ISOLATED_PROJECT 名下的资源，开发库 $DEV_CONTAINER 不在范围内）："
    show_isolated_residuals
    if [[ "$RESIDUAL_FOUND" != "0" ]]; then
        reasons+=("$ISOLATED_PROJECT 名下仍有残留容器/pod/网络（见上面的收摊证据）")
    fi
    if [[ "$port_after" != "0" ]]; then
        reasons+=("127.0.0.1:$PORT 仍在监听（可能还有孤儿进程：ss -ltnp | grep $PORT）")
    fi

    # 成功措辞只在「down 成功 + 容器消失 + 无残留 + 端口不再监听」四件事同时成立时打印。
    if [[ ${#reasons[@]} -eq 0 ]]; then
        info "收摊成功：容器 $CONTAINER 已消失，$ISOLATED_PROJECT 名下无残留容器/pod/网络，127.0.0.1:$PORT 已不再监听。"
        exit "$code"
    fi

    warn "收摊未成功：测试资源可能仍在运行，必须人工处理后再结束本轮会话。"
    local r
    for r in "${reasons[@]}"; do warn "  - $r"; done
    cat >&2 <<EOF
[aceso-test] 人工清理（确认项目名是 $ISOLATED_PROJECT 后再执行；绝不加 -f/--force/remove-orphans）：
  1) ${COMPOSE_ARR[*]:-podman compose} -p $ISOLATED_PROJECT -f $COMPOSE_FILE down
  2) ${RUNTIME:-podman} ps -a --format '{{.Names}}\t{{.Status}}' | grep -F '$ISOLATED_PROJECT'
  3) ${RUNTIME:-podman} pod ps | grep -F 'pod_$ISOLATED_PROJECT'
  4) ${RUNTIME:-podman} network ls | grep -F '$ISOLATED_PROJECT'
若第 1 条仍失败，优先看它的原始输出（常见原因是容器被别的进程占用）；
不要对名为 $DEV_CONTAINER 的开发库容器做任何操作 —— 它属于项目 aceso，不是本脚本的资源。
EOF

    if [[ "$code" == "0" ]]; then
        warn "退出码 3：测试本身通过，但收摊未成功（上面列了原因）；请在离开本轮会话前清理干净。"
        exit 3
    fi
    exit "$code"
}

# 收摊钩子尽早安装：从「启动容器」开始，任何一步失败或 Ctrl+C 都会走清理，
# 不会把自己启动的测试容器留在后台跨会话存活（2026-09-29 生命周期策略）。
trap cleanup EXIT

# ---- 2) 探测/准备测试库 -------------------------------------------------

port_open() {
    local p="$1"
    timeout 3 bash -c "exec 3<>/dev/tcp/$DB_HOST/$p" >/dev/null 2>&1
}

detect_runtime() {
    COMPOSE_ARR=()
    COMPOSE_CMD=()
    RUNTIME=""
    if command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
        COMPOSE_ARR=(podman compose)
        RUNTIME="podman"
    elif command -v podman-compose >/dev/null 2>&1; then
        COMPOSE_ARR=(podman-compose)
        RUNTIME="podman"
    elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        COMPOSE_ARR=(docker compose)
        RUNTIME="docker"
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE_ARR=(docker-compose)
        RUNTIME="docker"
    fi
    # 唯一的 compose 调用形态：永远显式带 -p 隔离项目名（双保险/三层里的第二层）。
    if [[ ${#COMPOSE_ARR[@]} -gt 0 ]]; then
        COMPOSE_CMD=("${COMPOSE_ARR[@]}" -p "$ISOLATED_PROJECT" -f "$COMPOSE_FILE")
        if ! compose_argv_has_project; then
            fail "内部不变量被破坏：compose 调用没有隔离项目名（argv：${COMPOSE_CMD[*]}）。"
            exit 1
        fi
    fi
}

container_exists() {
    local name="$1"
    [[ -n "$RUNTIME" ]] || return 1
    "$RUNTIME" container exists "$name" >/dev/null 2>&1
}

compose_up_test_db() {
    info "启动测试数据库容器（$TEST_CONTAINER，端口 $TEST_PORT，隔离项目 $ISOLATED_PROJECT）..."
    info "  命令：PITCHFORK_TEST_DB_PORT=$TEST_PORT PITCHFORK_TEST_DB_PASSWORD=<hidden> ${COMPOSE_CMD[*]} up -d"
    # 容器的 POSTGRES_PASSWORD 必须与本次使用的密码一致，否则 JDBC 会认证失败。
    if ! (
        cd "$ROOT_DIR/service-vertx-kotlin"
        PITCHFORK_TEST_DB_PORT="$TEST_PORT" \
        PITCHFORK_TEST_DB_PASSWORD="$PASSWORD" \
            "${COMPOSE_CMD[@]}" up -d
    ); then
        fail "容器启动失败（compose up 返回非 0）。"
        cat >&2 <<EOF
可尝试：
  1) 手动确认容器运行时可用：${COMPOSE_ARR[*]} version
  2) 查看端口占用：ss -ltnp | grep $TEST_PORT
  3) 手动启动一次看报错（必须带隔离项目名 -p $ISOLATED_PROJECT）：
       cd $BACKEND_DIR/apps/aceso
       PITCHFORK_TEST_DB_PASSWORD=<密码> ${COMPOSE_ARR[*]} -p $ISOLATED_PROJECT -f compose.test.yaml up -d
  4) 若不用容器：改用 --port 5432（开发库端口所在容器），或自行准备 55432 上的库。
EOF
        exit 1
    fi
    STARTED_CONTAINER=1
}

wait_for_container_ready() {
    local name="$1" waited=0
    info "等待容器 $name 就绪（最多 ${READY_TIMEOUT}s，pg_isready 探测）..."
    while (( waited < READY_TIMEOUT )); do
        if "$RUNTIME" exec "$name" pg_isready -U "$DB_USER" -d postgres >/dev/null 2>&1; then
            info "容器已就绪（用时 ${waited}s）。"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    fail "等待容器就绪超时（${READY_TIMEOUT}s）。"
    cat >&2 <<EOF
可尝试：
  1) 查看容器日志：$RUNTIME logs $name
  2) 放宽等待时间：ACESO_READY_TIMEOUT=120 ./scripts/aceso-test.sh
  3) 端口被占用时先释放 $TEST_PORT（ss -ltnp | grep $TEST_PORT）。
EOF
    exit 1
}

# 决定端口与容器名
detect_runtime

if [[ -n "$PORT" ]]; then
    info "使用 --port 指定的端口：$PORT"
    if [[ "$PORT" == "$TEST_PORT" ]]; then
        [[ "$CONTAINER_CHOSEN_BY_USER" == "1" ]] || CONTAINER="$TEST_CONTAINER"
    else
        [[ "$CONTAINER_CHOSEN_BY_USER" == "1" ]] || CONTAINER="$DEV_CONTAINER"
    fi
    if ! port_open "$PORT"; then
        if [[ "$PORT" == "$TEST_PORT" && -n "$RUNTIME" ]]; then
            warn "127.0.0.1:$PORT 当前不可连，将尝试用 compose 启动测试容器。"
            compose_up_test_db
            wait_for_container_ready "$CONTAINER"
        else
            fail "127.0.0.1:$PORT 不可连，且脚本不会自动启动这个端口对应的容器。"
            cat >&2 <<EOF
请任选一种出路：
  1) 启动测试专用容器后重跑（不加 --port，或 --port $TEST_PORT）：
       cd $BACKEND_DIR/apps/aceso
       PITCHFORK_TEST_DB_PASSWORD=<密码> ${COMPOSE_ARR[*]:-podman compose} -p $ISOLATED_PROJECT -f compose.test.yaml up -d
     （-p $ISOLATED_PROJECT 必须带：少了它会退回目录名 aceso，与开发库同项目）
  2) 先手动启动开发库容器，再用 --port $DEV_PORT 指向它（仍只操作 aceso_test）。
  3) 换一个已经在监听 $PORT 的测试库实例。
EOF
            exit 1
        fi
    fi
    info "127.0.0.1:$PORT 已可连，直接复用。"
else
    info "自动探测测试库：先看 127.0.0.1:$TEST_PORT 是否已可连 ..."
    if port_open "$TEST_PORT"; then
        PORT="$TEST_PORT"
        CONTAINER="${CONTAINER:-$TEST_CONTAINER}"
        info "127.0.0.1:$TEST_PORT 已可连，直接复用已起的容器（脚本不会停它）。"
    elif [[ -n "$RUNTIME" ]]; then
        PORT="$TEST_PORT"
        CONTAINER="${CONTAINER:-$TEST_CONTAINER}"
        info "127.0.0.1:$TEST_PORT 未监听，检测到容器运行时：$RUNTIME"
        compose_up_test_db
        wait_for_container_ready "$CONTAINER"
    else
        fail "找不到可用的测试数据库，也没有可用的容器运行时（podman / docker）。"
        cat >&2 <<EOF
请任选一种出路：
  1) 启动测试专用容器（需要一个可用的 podman 或 docker；-p 项目名必须带）：
       cd $BACKEND_DIR/apps/aceso
       PITCHFORK_TEST_DB_PASSWORD=<密码> podman compose -p $ISOLATED_PROJECT -f compose.test.yaml up -d
     然后重跑本脚本；脚本会自动复用 127.0.0.1:$TEST_PORT。
  2) 使用开发库端口所在容器（请先确认该容器在跑）：
       ./scripts/aceso-test.sh --port $DEV_PORT
     库名用 --database 指定；脚本只在库不存在时 CREATE，绝不 DROP。

脚本不会静默降级到其它端口或跳过测试。
EOF
        exit 1
    fi
fi

# 容器不可用时，是否还有宿主机 psql 兜底
HOST_PSQL=0
command -v psql >/dev/null 2>&1 && HOST_PSQL=1
CONTAINER_PSQL=0
if [[ -n "$RUNTIME" ]] && container_exists "$CONTAINER"; then
    CONTAINER_PSQL=1
fi

if [[ "$CONTAINER_PSQL" == "0" && "$HOST_PSQL" == "0" ]]; then
    fail "无法确认库 $DB_NAME 是否存在：容器 '$CONTAINER' 不可用，宿主机也没有 psql。"
    cat >&2 <<EOF
注意：127.0.0.1:$PORT 上确实有服务在监听，但本脚本既 exec 不进它的容器，
      宿主机也没有 psql，因此无法查询/创建目标库。
常见原因：
  a) 当前 shell 里容器运行时不可用（例如 podman 无权限写 runroot，或用了 rootful 容器而
     当前是非 root 的 rootless podman）；
  b) $PORT 上跑的是不经 podman/docker 管理的 PostgreSQL。

请任选一种出路：
  1) 指定一个确实存在、且里面有 psql 的容器名（用 '${RUNTIME:-podman}' ps 查）：
       ./scripts/aceso-test.sh --container <容器名>
  2) 让容器运行时在当前 shell 可用后重跑（脚本会自动启动 $TEST_CONTAINER）：
       ./scripts/aceso-test.sh
  3) 库里已经齐备、只是想跑测试（脚本不会删库）：
       ./scripts/aceso-test.sh --database <库名> --no-migrate
EOF
    exit 1
fi

# 校验密码能真正登录（复用旧容器时最常见的坑：旧容器用的是另一个密码）
if [[ "$CONTAINER_PSQL" == "1" ]]; then
    info "校验密码可登录 $CONTAINER（不显示密码）..."
    if ! "$RUNTIME" exec -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
            psql -h 127.0.0.1 -p 5432 -U "$DB_USER" -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
        fail "用当前密码无法登录容器 $CONTAINER 里的 PostgreSQL。"
        cat >&2 <<EOF
最常见原因：这个容器是用另一个密码创建的（例如更早的 compose 默认值 pitchfork-test-only），
而你现在提供的密码与它不一致。

请任选一种出路：
  1) 丢掉旧容器重建（会销毁其中的测试数据，仅限 $DB_NAME 测试库；只 down 测试隔离项目，
       不加 -f/--force，也不碰开发库）：
       ${COMPOSE_ARR[*]:-podman compose} -p $ISOLATED_PROJECT -f $COMPOSE_FILE down
     然后重跑本脚本。
  2) 确认 apps/aceso/.env 或 PITCHFORK_DB_PASSWORD 里的密码与容器一致。
  3) 换容器：./scripts/aceso-test.sh --container <容器名>
EOF
        exit 1
    fi
    info "密码校验通过。"
fi

# ---- 2b) 解析目标库名（不猜） -------------------------------------------

if [[ -z "$DB_NAME" ]]; then
    if [[ "$PORT" == "$TEST_PORT" && "$CONTAINER" == "$TEST_CONTAINER" ]]; then
        DB_NAME="aceso_test"
        info "未指定 --database：使用一次性测试容器自带的库 $DB_NAME。"
        info "  指向既有库时请显式 --database <库名>（脚本不猜你的库）。"
    else
        fail "缺少 --database <库名>：目标看似是既有实例（$DB_HOST:$PORT，容器 '$CONTAINER'），脚本不猜库名。"
        cat >&2 <<EOF
请显式给出库名，例如：
  ./scripts/aceso-test.sh --port $PORT --database aceso --no-migrate

说明：
  - 脚本不再硬编码 aceso_test，也不会替你决定该连哪个库；
  - 已有库一律保留（不 DROP/CREATE）；只有库不存在时才会 CREATE 一次；
  - --no-migrate 表示不执行 Flyway，只复用既有 schema。
EOF
        exit 2
    fi
fi

# ---- 3) 确认目标库存在（只 CREATE，不 DROP） ---------------------------

psql_admin() {
    if [[ "$CONTAINER_PSQL" == "1" ]]; then
        "$RUNTIME" exec "$@" "$CONTAINER" psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1
    else
        PGPASSWORD="$PASSWORD" psql -h "$DB_HOST" -p "$PORT" -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1
    fi
}

psql_admin_scalar() {
    local sql="$1"
    if [[ "$CONTAINER_PSQL" == "1" ]]; then
        "$RUNTIME" exec "$CONTAINER" psql -U "$DB_USER" -d postgres -tAc "$sql" 2>/dev/null
    else
        PGPASSWORD="$PASSWORD" psql -h "$DB_HOST" -p "$PORT" -U "$DB_USER" -d postgres -tAc "$sql" 2>/dev/null
    fi
}

# 只在库不存在时建一次；已有库一律保留（2026-11-21：不再 DROP DATABASE）。
ensure_database_exists() {
    local exists
    exists="$(psql_admin_scalar "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | tr -d '[:space:]')"
    if [[ "$exists" == "1" ]]; then
        info "目标库 $DB_NAME 已存在：保留现有内容（不 DROP、不重建）。"
        return 0
    fi
    info "目标库 $DB_NAME 不存在：仅创建，不 DROP。"
    psql_admin -c "CREATE DATABASE $DB_NAME;" || {
        fail "CREATE DATABASE $DB_NAME 失败。请检查容器/实例日志与 $DB_USER 的建库权限。"
        exit 1
    }
    info "创建完成。"
}

ensure_database_exists

# ---- 4) 跑测试（单次 gradle 调用；顺序不再有硬约束） --------------------

GRADLE_DB_ARGS=(
    "-Dintegration.db.host=$DB_HOST"
    "-Dintegration.db.port=$PORT"
    "-Dintegration.db.database=$DB_NAME"
    "-Dintegration.db.user=$DB_USER"
)
# 指向既有库、不想让测试推进迁移时用（不动 schema，只复用既有表）
[[ "$NO_MIGRATE" == "1" ]] && GRADLE_DB_ARGS+=("-Dintegration.db.migrate=false")

GRADLEW="$BACKEND_DIR/gradlew"
GRADLE_TASKS=(":libs:healthcare:test" ":apps:aceso:test")

# gradlew 的可执行位：git index 里 gradlew 登记为 100644，且本仓库 core.fileMode=false，
# 所以主检出里的 +x 是用户手工 chmod 的，任何新克隆 / worktree 都不会带上它，直接
# ./gradlew 会得到 "env: ./gradlew: 权限不够"（126/127），gradle 根本没跑。
ensure_gradlew_executable() {
    if [[ ! -f "$GRADLEW" ]]; then
        fail "找不到 gradle wrapper：$GRADLEW"
        exit 1
    fi
    if [[ -x "$GRADLEW" ]]; then
        return 0
    fi
    warn "gradlew 缺少可执行位（git index 里是 100644，且 core.fileMode=false，克隆/worktree 不会带上 +x）。"
    info "  正在修复：chmod +x $GRADLEW"
    if ! chmod +x "$GRADLEW"; then
        fail "chmod +x $GRADLEW 失败（权限不足或只读文件系统）——这就是接下来 gradle 失败的真实原因。"
        exit 1
    fi
    info "  已修复 $GRADLEW 的可执行位（只影响本工作树）。"
}

run_tests() {
    local extra=()
    [[ "$OFFLINE" == "1" ]] && extra+=(--offline)
    [[ ${#PASSTHROUGH_ARGS[@]} -gt 0 ]] && extra+=("${PASSTHROUGH_ARGS[@]}")

    ensure_gradlew_executable

    local rendered="PITCHFORK_DB_PASSWORD=<hidden> ./gradlew ${GRADLE_TASKS[*]} --rerun ${GRADLE_BASE_ARGS[*]} ${GRADLE_DB_ARGS[*]}"
    [[ ${#extra[@]} -gt 0 ]] && rendered+=" ${extra[*]}"

    info "---------------------------------------------------------------"
    info "运行 ${GRADLE_TASKS[*]}"
    printf '[aceso-test] 实际执行的完整命令（密码已隐藏）：\n  %s\n' "$rendered"
    [[ "$NO_MIGRATE" == "1" ]] && info "本次不执行 Flyway（-Dintegration.db.migrate=false）"
    info "---------------------------------------------------------------"

    local rc=0
    (
        cd "$BACKEND_DIR"
        env "PITCHFORK_DB_PASSWORD=$PASSWORD" ./gradlew "${GRADLE_TASKS[@]}" --rerun \
            "${GRADLE_BASE_ARGS[@]}" "${GRADLE_DB_ARGS[@]}" ${extra[@]+"${extra[@]}"}
    ) || rc=$?

    if [[ "$rc" == "126" || "$rc" == "127" ]]; then
        warn "gradle 根本没跑起来（exit $rc，通常是 wrapper 不可执行或 java 不在 PATH）；这不是测试失败。"
        warn "  检查：ls -l $GRADLEW（应有 x 位）；java -version"
    fi
    return "$rc"
}

RC=0
run_tests || RC=$?

# ---- 5) 汇总 ------------------------------------------------------------

info "================ 结果汇总 ================"
if [[ "$RC" == "0" ]]; then
    info ":libs:healthcare:test + :apps:aceso:test  全部通过（exit 0）"
else
    warn ":libs:healthcare:test + :apps:aceso:test  有失败（exit $RC）"
    info "  两个 task 在同一次 gradle 调用里跑，失败信息已在上面完整输出。"
fi
info "目标库：$DB_NAME @ $DB_HOST:$PORT（用户 $DB_USER）"
info "  - 不 DROP/CREATE 已存在的库；数据隔离靠测试自身的 fixture 前缀 + 清理 + 残差断言"
if [[ "$NO_MIGRATE" == "1" ]]; then
    info "  - Flyway：未执行（-Dintegration.db.migrate=false，复用既有 schema）"
else
    info "  - Flyway：已执行 migrate（baselineOnMigrate + ignore missing/ignored + outOfOrder）"
fi
info "逐类 tests/failures/errors/skipped 见各模块 build/test-results/test/*.xml"
info "========================================="

if [[ "$RC" != "0" ]]; then
    exit 1
fi
exit 0
