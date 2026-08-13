#!/usr/bin/env bash
#
# setup.sh — 在任意项目上初始化 kanban 角色流水线:
#   1. 创建角色 profiles (plan/dev/tester/review, 已存在则跳过)
#   2. 创建看板 (board, --default-workdir 指向项目)
#   3. 写入薄 SOUL.md 到各 profile (模式 C; --no-so 跳过)
#   4. 生成 .kanban-role.conf 与 .gitignore 追加项
#
# 用法:
#   bash setup.sh --project /path/to/proj --board <slug> [--no-so]
#
# 环境变量(可选覆盖):
#   KANBAN_ROLE_CODEX_PLAN   codex profile 名, 默认 terra
#   KANBAN_ROLE_CODEX_DEV    默认 deepseek
#   KANBAN_ROLE_CODEX_TESTER 默认 luna
#   KANBAN_ROLE_CODEX_REVIEW 默认 luna
#
set -euo pipefail

PROJECT=""
BOARD=""
NO_SO=0
while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --board)   BOARD="$2";   shift 2 ;;
    --no-so)   NO_SO=1;      shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -n "$PROJECT" ] || { echo "用法: bash setup.sh --project <dir> --board <slug> [--no-so]" >&2; exit 1; }
PROJECT="$(realpath "$PROJECT")"
[ -d "$PROJECT/.git" ] || { echo "❌ $PROJECT 不是 git 仓库" >&2; exit 1; }
BOARD="${BOARD:-$(basename "$PROJECT")}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 角色 → codex profile 映射 (只用于 SOUL 文本注入)
declare -A CODEX=(
  [plan]="${KANBAN_ROLE_CODEX_PLAN:-terra}"
  [dev]="${KANBAN_ROLE_CODEX_DEV:-deepseek}"
  [tester]="${KANBAN_ROLE_CODEX_TESTER:-luna}"
  [review]="${KANBAN_ROLE_CODEX_REVIEW:-luna}"
)

ROLES=(plan dev tester review)

echo "==> 项目: $PROJECT  看板: $BOARD"

# 1. profiles
for r in "${ROLES[@]}"; do
  if hermes profile show "$r" >/dev/null 2>&1; then
    echo "    profile $r 已存在,跳过"
  else
    echo "   创建 profile $r (codex: ${CODEX[$r]})"
    hermes profile create "$r" --clone --no-alias \
      --description "$(case $r in
        plan)   echo "计划角色: 撰写实施计划,由 codex -p ${CODEX[$r]} 起草" ;;
        dev)    echo "开发角色: 按计划实现,由 codex -p ${CODEX[$r]} 实现" ;;
        tester) echo "测试角色: 按计划运行测试,由 codex -p ${CODEX[$r]} 执行" ;;
        review) echo "评审角色: 独立终审验收,由 codex -p ${CODEX[$r]} 执行" ;;
      esac)"
  fi
done

# 2. board
if hermes kanban boards list 2>/dev/null | grep -qw "$BOARD"; then
  echo "    board $BOARD 已存在"
else
  echo "   创建 board $BOARD (default-workdir=$PROJECT)"
  hermes kanban boards create "$BOARD" --default-workdir "$PROJECT" --switch
fi

# 3. SOUL (模式 C)
if [ "$NO_SO" -eq 1 ]; then
  echo "   跳过 SOUL 写入 (--no-so)"
else
  for r in "${ROLES[@]}"; do
    SOUL="$HOME/.hermes/profiles/$r/SOUL.md"
    sed -e "s|@CODEX@|${CODEX[$r]}|g" \
        -e "s|@BOARD@|$BOARD|g" \
        -e "s|@PROJECT@|$PROJECT|g" \
        "$HERE/so/$r.SOUL.md" > "$SOUL"
    chmod 600 "$SOUL"
    echo "   写入 $SOUL"
  done
fi

# 4. 项目内配置 + gitignore
cat > "$PROJECT/.kanban-role.conf" <<EOF
# kanban-role-pipeline 配置 (chain.sh 读取)
KANBAN_ROLE_PROJECT="$PROJECT"
KANBAN_ROLE_BOARD="$BOARD"
EOF
if ! grep -q "^\\.hermes/loop/$" "$PROJECT/.gitignore" 2>/dev/null; then
  printf '\n# kanban worker 报告(不入库)\n.hermes/loop/\n' >> "$PROJECT/.gitignore"
fi

echo "✅ 完成。下一步:"
echo "   bash $HERE/chain.sh \"任务描述\""
echo "   (可先 export TEST_CMD=\"...\" 钉死硬性验证命令)"
