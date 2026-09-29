#!/usr/bin/env bash
#
# aceso-test.sh —— 一条命令跑完 Aceso / Healthcare 的数据库集成测试。
#
# 用法：
#   cd service-vertx-kotlin && ./scripts/aceso-test.sh
#
# 它会自动完成：取密码 → 找到或拉起测试 PostgreSQL → 重置 aceso_test →
# 跑 :libs:healthcare:test 与 :apps:aceso:test（顺序是硬约束：先 healthcare、后 aceso）→ 收摊（默认停掉自己起的容器）。
#
# 生命周期（2026-09-29 仓库策略，见根 AGENTS.md「服务与资源生命周期」）：
#   - 开发服务（Aceso API 8422 / UI 4324 / Identity 8420 / Nexus 8421 / 开发库 5432）
#     只由用户启停；本脚本不会启动、停止、杀进程或重绑这些服务。
#   - 测试库容器（pitchfork-aceso-test-db，端口 55432）属于「测试资源」，可以由
#     agent/脚本用 -d 后台启动；但本脚本在退出时（含失败与 Ctrl+C）必定 down 掉
#     自己启动的那个，并打印 `podman ps` 证据确认已消失，绝不跨会话留存。
#   - `--port 5432` 只借用用户已启动的开发库容器来放 aceso_test，脚本不会停它。
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
#   - 只允许操作名为 aceso_test 的库（ACESO_TEST_DB 覆盖后仍会被校验）；绝不连接 aceso 业务库。
#   - 密码只从环境变量或 apps/aceso/.env 读取，绝不回显到 stdout / 日志。
#   - 密码通过环境变量传给 Gradle，不进入命令行参数。
#
# 与 scripts/aceso-integration-tests.sh 的关系：后者是旧的窄口径入口（只跑 2 个
# Healthcare 类、要求手动 export 密码、默认端口 55432）；本脚本是推荐入口，覆盖
# 两个模块的全部测试类。详见 docs/aceso-test-database.md。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$BACKEND_DIR/.." && pwd)"
COMPOSE_FILE="$BACKEND_DIR/apps/aceso/compose.test.yaml"
ENV_FILE="$BACKEND_DIR/apps/aceso/.env"

# ---- 常量 ---------------------------------------------------------------

DB_NAME="${ACESO_TEST_DB:-aceso_test}"
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
SKIP_RESET="${ACESO_SKIP_DB_RESET:-0}"
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
  --port <55432|5432>    指定测试库端口，跳过自动探测。
                         55432 = 测试专用容器（默认，容器名 pitchfork-aceso-test-db）
                         5432  = 开发库端口所在的容器（容器名 pitchfork-aceso-db），
                                 仍只操作 aceso_test，不碰 aceso 业务库。
  --container <name>     指定用于执行 psql / pg_isready 的容器名（覆盖上面的默认值）。
  --keep                 跑完保留容器，方便下次更快（下次若端口已通会自动复用）。
                         注意：保留的容器必须由你在本轮结束前收掉，不得跨会话存活；
                         脚本会提示收尾命令。
  --skip-reset           不执行 DROP/CREATE aceso_test（等价于 ACESO_SKIP_DB_RESET=1）。
  --offline              给 gradlew 加 --offline（依赖已缓存时可加速、避免联网）。
  -h, --help             显示本帮助。

执行顺序（硬约束，实测依据）：
  先 :libs:healthcare:test，再 :apps:aceso:test。
  原因是 :apps:aceso 的 testRuntimeClasspath 是全集（database / common / inventories /
  nursing / pharmacy / healthcare / dining），先跑它会把 dining 段迁移 V600 铺进库；
  而 :libs:healthcare 的 classpath 不含 dining，它再做 Flyway validate 时会直接失败：
  "Detected applied migration not resolved locally: 600"。
  反过来（healthcare 先、apps 后）不会出问题：healthcare 只铺自己那套子集迁移，apps 随后
  解析的是超集，不存在「已应用但解析不到」。
  兜底的 schema 预检（public.stocks）只在「前置 gradle 成功」且 --skip-reset（沿用上一轮
  库内容）时才跑；默认流程刚重置过库，public.stocks 必然不存在，此时预检无意义因而跳过。
  ⚠️ 若前置 gradle 以非 0 退出，一律跳过预检并打印「前置 gradle 未成功（exit N），schema
  预检无意义」，也不把失败归因成 classpath 问题；真实原因保留在 gradle 的原始输出里。

容器项目隔离（2026-09-29 险情修复）：
  测试 compose 固定在项目 pitchfork-aceso-test（compose.test.yaml 顶层 name:，
  每次调用还显式带 -p pitchfork-aceso-test，另有 COMPOSE_PROJECT_NAME 兜底）。
  脚本在执行任何 down 之前会断言项目名等于 pitchfork-aceso-test，不等就拒绝执行并报错退出；
  绝不使用 -f/--force，也不使用 remove-orphans。原因是 compose.test.yaml 与开发库
  compose.yaml 同在 apps/aceso，项目名一旦退回目录名 'aceso'，测试的 down 就会删掉
  用户开发库 pitchfork-aceso-db 所在的 pod 与网络（详见 compose.test.yaml 顶部注释）。

密码来源（按顺序，取到即用，绝不回显）：
  1) 环境变量 PITCHFORK_DB_PASSWORD
  2) 环境变量 PITCHFORK_TEST_DB_PASSWORD
  3) apps/aceso/.env 里的 PITCHFORK_DB_PASSWORD

示例：
  # 最常用：.env 里已填好密码，直接跑
  ./scripts/aceso-test.sh

  # 用开发库端口上的容器，只重置 aceso_test
  ./scripts/aceso-test.sh --port 5432

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
        --skip-reset)
            SKIP_RESET=1
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

# ---- 护栏：只允许 aceso_test -------------------------------------------

if [[ "$DB_NAME" != "aceso_test" ]]; then
    fail "拒绝执行：只能操作 aceso_test，当前为 '$DB_NAME'（ACESO_TEST_DB）。"
    echo "  本脚本不会连接 aceso 业务库或共享开发库。" >&2
    exit 2
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
     脚本仍只 DROP/CREATE $DB_NAME，不会碰 aceso 业务库。

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
    fail "无法重置 $DB_NAME：容器 '$CONTAINER' 不存在/不可用，宿主机也没有 psql。"
    cat >&2 <<EOF
注意：127.0.0.1:$PORT 上确实有服务在监听，但本脚本既 exec 不进它的容器，
      宿主机也没有 psql，所以没有安全的办法 DROP/CREATE $DB_NAME。
常见原因：
  a) 当前 shell 里容器运行时不可用（例如 podman 无权限写 runroot，或用了 rootful 容器而
     当前是非 root 的 rootless podman）；
  b) $PORT 上跑的是不经 podman/docker 管理的 PostgreSQL。

请任选一种出路：
  1) 指定一个确实存在、且里面有 psql 的容器名（用 '${RUNTIME:-podman}' ps 查）：
       ./scripts/aceso-test.sh --container <容器名>
  2) 让容器运行时在当前 shell 可用后重跑（脚本会自动启动 $TEST_CONTAINER）：
       ./scripts/aceso-test.sh
  3) 自己准备可用的测试库，再手工重置：
       psql -h $DB_HOST -p $PORT -U $DB_USER -d postgres \\
         -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE);" -c "CREATE DATABASE $DB_NAME;"
     然后 ./scripts/aceso-test.sh --skip-reset
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

# ---- 3) 重置库（只用 aceso_test） --------------------------------------

psql_admin() {
    if [[ "$CONTAINER_PSQL" == "1" ]]; then
        "$RUNTIME" exec "$@" "$CONTAINER" psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1
    else
        PGPASSWORD="$PASSWORD" psql -h "$DB_HOST" -p "$PORT" -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1
    fi
}

reset_db() {
    if [[ "$SKIP_RESET" == "1" ]]; then
        info "SKIP_RESET / --skip-reset：保留现有 $DB_NAME 内容，不重置。"
        return 0
    fi
    info "重置独立测试库（只操作 $DB_NAME）：DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE); CREATE DATABASE $DB_NAME;"
    if [[ "$CONTAINER_PSQL" == "1" ]]; then
        info "  通过容器 '$CONTAINER' 内的 psql 执行（宿主机无需安装 psql）。"
        info "  等价命令：$RUNTIME exec $CONTAINER psql -U $DB_USER -d postgres -v ON_ERROR_STOP=1 -c '<下一条>'"
        "$RUNTIME" exec "$CONTAINER" psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
            -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE);" || {
                fail "DROP DATABASE $DB_NAME 失败。请确认容器 '$CONTAINER' 正常、$DB_USER 有权限。"
                exit 1
            }
        "$RUNTIME" exec "$CONTAINER" psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
            -c "CREATE DATABASE $DB_NAME;" || {
                fail "CREATE DATABASE $DB_NAME 失败。请检查容器日志：$RUNTIME logs $CONTAINER"
                exit 1
            }
    else
        info "  容器不可用，使用宿主机 psql（$DB_HOST:$PORT）。"
        psql_admin -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE);" || {
            fail "DROP DATABASE $DB_NAME 失败（宿主机 psql，$DB_HOST:$PORT）。"
            exit 1
        }
        psql_admin -c "CREATE DATABASE $DB_NAME;" || {
            fail "CREATE DATABASE $DB_NAME 失败（宿主机 psql，$DB_HOST:$PORT）。"
            exit 1
        }
    fi
    info "重置完成。"
}

reset_db

# ---- 4) 跑测试 ----------------------------------------------------------

GRADLE_DB_ARGS=(
    "-Dintegration.db.host=$DB_HOST"
    "-Dintegration.db.port=$PORT"
    "-Dintegration.db.database=$DB_NAME"
    "-Dintegration.db.user=$DB_USER"
)

GRADLEW="$BACKEND_DIR/gradlew"

# gradlew 的可执行位：git index 里 gradlew 登记为 100644，且本仓库 core.fileMode=false，
# 所以主检出里的 +x 是用户手工 chmod 的，任何新克隆 / worktree 都不会带上它，直接
# ./gradlew 会得到 "env: ./gradlew: 权限不够"（126/127），gradle 根本没跑。
# 取舍：这里选「检测 + chmod +x 并打印」，而不是「sh ./gradlew」。
#   - chmod 修的是根因，且与脚本里打印的 ./gradlew 完全一致（可直接复制）；
#   - 本仓库 core.fileMode=false，chmod 不会污染 git 状态或产生 diff 噪音；
#   - 若改用 sh ./gradlew，打印出来的命令就必须改成 sh ./gradlew，否则「实际执行的
#     完整命令」又是一句假话；而 /bin/sh 未必是 bash，gradlew 官方只保证 bash 语义。
# chmod 失败会明确报错（不静默、不掩盖），因为那才是后面 gradle 失败的真实原因。
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
        fail "chmod +x $GRADLEW 失败（权限不足或只读文件系统）——这就是接下来 gradle 失败的真实原因，不是 classpath 问题。"
        cat >&2 <<EOF
请先修好再重跑：
  chmod +x $GRADLEW
仓库侧（推荐，交给维护者）：
  git update-index --chmod=+x service-vertx-kotlin/gradlew
EOF
        exit 1
    fi
    if [[ ! -x "$GRADLEW" ]]; then
        fail "chmod 已执行但 $GRADLEW 仍不可执行（请检查挂载选项或 ACL）。"
        exit 1
    fi
    info "  已修复 $GRADLEW 的可执行位（只影响本工作树；仓库侧修复为 git update-index --chmod=+x）。"
}

run_module_tests() {
    local task="$1"
    local extra=()
    [[ "$OFFLINE" == "1" ]] && extra+=(--offline)
    [[ ${#PASSTHROUGH_ARGS[@]} -gt 0 ]] && extra+=("${PASSTHROUGH_ARGS[@]}")

    ensure_gradlew_executable

    local rendered="PITCHFORK_DB_PASSWORD=<hidden> ./gradlew $task --rerun ${GRADLE_BASE_ARGS[*]} ${GRADLE_DB_ARGS[*]}"
    [[ ${#extra[@]} -gt 0 ]] && rendered+=" ${extra[*]}"

    info "---------------------------------------------------------------"
    info "运行 $task"
    printf '[aceso-test] 实际执行的完整命令（密码已隐藏）：\n  %s\n' "$rendered"
    info "---------------------------------------------------------------"

    local rc=0
    (
        cd "$BACKEND_DIR"
        env "PITCHFORK_DB_PASSWORD=$PASSWORD" ./gradlew "$task" --rerun \
            "${GRADLE_BASE_ARGS[@]}" "${GRADLE_DB_ARGS[@]}" ${extra[@]+"${extra[@]}"}
    ) || rc=$?

    if [[ "$rc" == "126" || "$rc" == "127" ]]; then
        warn "$task 的 gradle 根本没跑起来（exit $rc，通常是 wrapper 不可执行或 java 不在 PATH）；这不是测试失败，也不是 classpath 问题。"
        warn "  检查：ls -l $GRADLEW（应有 x 位）；java -version"
    fi
    return "$rc"
}

# 在目标库 aceso_test 里执行一条标量查询（用于跑 healthcare 前的防御性 schema 检查）。
db_scalar() {
    local sql="$1"
    if [[ "$CONTAINER_PSQL" == "1" ]]; then
        "$RUNTIME" exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$sql" 2>/dev/null
    else
        PGPASSWORD="$PASSWORD" psql -h "$DB_HOST" -p "$PORT" -U "$DB_USER" -d "$DB_NAME" -tAc "$sql" 2>/dev/null
    fi
}

# 防御性检查（兜底，只用于「不重置库、沿用上一轮内容」的 --skip-reset 情形）：
# :libs:healthcare:test 的 MedicalOrderIntegrationTest 会往 public.materials /
# public.lots / public.stocks 写 fixture，这三张表由 libs/inventories 的迁移 V200/V201
# 创建。正常情况下 healthcare 自己的 Flyway migrate 就会建出来（依赖链
# healthcare → nursing → inventories，nursing 用 api 暴露 inventories，
# DatabaseConfig.migrate 扫 classpath:db/migration）；一旦缺失，说明 inventories 的
# 迁移没进本次 classpath，此时提前报错好过让用户面对一堆
# "relation public.stocks does not exist"。
# 注意：刚 DROP/CREATE 过的空库（默认流程）里 public.stocks 必然不存在，此时调用本函数
# 一律会失败 —— 所以默认流程不调用它，只打印说明；顺序（healthcare 先）才是根因修复，
# 本预检只是兜底，不能替代顺序。
require_healthcare_schema() {
    local stocks
    stocks="$(db_scalar "SELECT to_regclass('public.stocks') IS NOT NULL" | tr -d '[:space:]')"
    if [[ "$stocks" == "t" ]]; then
        info "schema 预检：$DB_NAME 里 public.stocks 存在 ✅"
        return 0
    fi
    fail "承接上一轮内容的 $DB_NAME 里没有 public.stocks（预检结果：'$stocks'，空表示连查询本身都没成功）。"
    cat >&2 <<EOF
原因：:libs:healthcare:test 的 MedicalOrderIntegrationTest 会 INSERT 到
      public.materials / public.lots / public.stocks，这些表来自 libs/inventories
      的迁移 V200__create_inventory_tables.sql / V201__add_stock_operations.sql。
      若它们不在本次运行的 classpath 上，healthcare 的 migrate 就建不出这些表。

正常情况：healthcare 依赖链是 healthcare → nursing → inventories（nursing 用 api 暴露），
      本预检是在 --skip-reset 下做兜底体检（默认流程会先重置库，不会走这里）。

请任选一种出路：
  1) 先确认 libs/nursing/build.gradle.kts 里仍是对 libs:inventories 的 api 依赖；
  2) 手工看一眼库：psql -h $DB_HOST -p $PORT -U $DB_USER -d $DB_NAME -c "\\dt public.*"
  3) 只跑 aceso 侧：./scripts/aceso-test.sh -- <给 gradlew 的额外参数>，
     或直接 ./gradlew :apps:aceso:test -Dintegration.db.host=... -Dintegration.db.port=...
EOF
    exit 1
}

# ---- 4b) 执行顺序：先 :libs:healthcare:test，再 :apps:aceso:test --------------
# 顺序是**硬约束**，不是偏好（2026-09-29 调度者在干净库上单写入者实测）：
#   把 5432 上的 aceso_test 重置为空库，按「apps 先 → healthcare 后」跑：
#     - :apps:aceso:test 通过（它的 classpath 含 dining，把 V600 等 dining 段迁移铺进了库）；
#     - 随后 :libs:healthcare:test 挂在
#         org.flywaydb.core.api.exception.FlywayValidateException:
#         Validate failed: Migrations have failed validation —
#         Detected applied migration not resolved locally: 600
#   V600 是 Aceso dining 段；:libs:healthcare 的 classpath 不含 dining，所以 Flyway 校验时
#   看到「库里已应用、本地解析不到」→ 直接失败。也就是说 :apps:aceso 的 classpath 是全集
#   （含 dining/pharmacy），先跑它会污染 healthcare 的校验。
#   反过来（healthcare 先、apps 后）不会出问题：healthcare 只铺它自己那套子集迁移；apps
#   随后解析的是**超集**，不存在「已应用但解析不到」。
# 其它相关事实：
#   - healthcare 自身的 classpath 已覆盖 public.stocks（healthcare → nursing → inventories，
#     nursing 用 api 暴露 inventories），所以它先跑能自己把表建起来；
#   - healthcare 里的 ElderlyDeathIntegrationTest / NursingIncidentHandoverIntegrationTest
#     会 DROP/CREATE aceso_test，因此它们之后再有模块依赖库内容是不可靠的，apps 在后正好
#     由它自己的 migrate 重新铺满；
#   - require_healthcare_schema 只是兜底，不能替代顺序：默认流程刚重置过库，
#     public.stocks 必然不存在，所以预检只在「前置 gradle 成功 且 --skip-reset」时才跑。
RC_ACESO=0
RC_HEALTHCARE=0
run_module_tests ":libs:healthcare:test" || RC_HEALTHCARE=$?

if [[ "$RC_HEALTHCARE" != "0" ]]; then
    warn "前置 gradle 未成功（exit $RC_HEALTHCARE），schema 预检无意义：跳过 public.stocks 预检。"
    info "  真实原因保留在上面 :libs:healthcare:test 的原始输出里，本脚本不做任何 classpath 归因。"
    info "  :apps:aceso:test 仍会照跑一遍；若它同样失败，请以第一段日志为准。"
elif [[ "$SKIP_RESET" == "1" ]]; then
    # 只有「沿用上一轮库内容」时，public.stocks 才可能有意义地作为兜底体检。
    require_healthcare_schema
else
    info "本轮刚重置过 $DB_NAME：public.stocks 必然还不存在（由 healthcare 自己的 Flyway migrate 建），故不做 DB 预检。"
    info "  若 healthcare 之后真报 relation public.stocks does not exist，那才是 classpath 里没有 inventories 迁移（V200/V201）。"
fi

run_module_tests ":apps:aceso:test" || RC_ACESO=$?

# ---- 5) 汇总 ------------------------------------------------------------

info "================ 结果汇总 ================"
if [[ "$RC_HEALTHCARE" == "0" ]]; then
    info ":libs:healthcare:test  通过（exit 0）"
else
    warn ":libs:healthcare:test  失败（exit $RC_HEALTHCARE）"
fi
if [[ "$RC_ACESO" == "0" ]]; then
    info ":apps:aceso:test       通过（exit 0）"
else
    warn ":apps:aceso:test       失败（exit $RC_ACESO）"
fi
info "执行顺序（硬约束）：:libs:healthcare:test（子集 classpath）→ :apps:aceso:test（全集：含 dining/pharmacy）"
info "  原因：apps 的 classpath 含 dining 段（V600）；若它先跑，healthcare 的 Flyway validate 会报"
info "        'Detected applied migration not resolved locally: 600'（healthcare 的 classpath 不含 dining）。"
info "测试库：$DB_NAME @ $DB_HOST:$PORT（用户 $DB_USER）"
info "逐类 tests/failures/errors/skipped 见各模块 build/test-results/test/*.xml"
info "========================================="

if [[ "$RC_HEALTHCARE" != "0" || "$RC_ACESO" != "0" ]]; then
    exit 1
fi
exit 0
