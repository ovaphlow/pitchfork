#!/usr/bin/env bash
#
# chain.sh — 在目标项目看板上创建「计划→开发→测试→终审」四角色卡片链。
#
# 用法:
#   bash chain.sh "任务描述" [--no-wait] [--force] [--mode so|prompt]
#
# 环境变量 (或项目下 .kanban-role.conf):
#   KANBAN_ROLE_PROJECT  项目目录 (默认: 当前目录)
#   KANBAN_ROLE_BOARD    看板 slug (默认: 项目目录名)
#   TEST_CMD             硬性验证命令(多行用换行分隔), 嵌入计划+测试卡 body
#   PLAN_APPROVAL=manual 计划完成后开发卡保持 blocked, 需人工 unblock 才放行
#   WAIT_MAX=<分钟>      等待流水线结束的最长分钟数 (默认 0=无限; --no-wait 跳过)
#   LOCK_STALE_AFTER=<秒> 锁过期自动清理 (默认 21600=6h)
#
# 模式:
#   so     (默认) 建卡链, dispatcher 自动派单 (需 setup.sh 已建 profiles)
#   prompt 只输出每阶段的 codex exec 提示词(含完整角色要求), 不建卡 —— 模式 A 用法
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 参数解析 ----------
NO_WAIT=0; FORCE=0; MODE=so; ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-wait) NO_WAIT=1; shift ;;
    --force)   FORCE=1;   shift ;;
    --mode)    MODE="${2:-so}"; shift 2 ;;
    *)         ARGS+=("$1"); shift ;;
  esac
done
TASK="${ARGS[0]:-}"
[ -n "$TASK" ] || { echo "用法: bash chain.sh \"任务描述\" [--no-wait] [--force] [--mode so|prompt]" >&2; exit 1; }

# ---------- 配置 ----------
[ -f .kanban-role.conf ] && { set -a; source .kanban-role.conf; set +a; }
PROJECT="${KANBAN_ROLE_PROJECT:-$(pwd)}"
BOARD="${KANBAN_ROLE_BOARD:-$(basename "$PROJECT")}"
[ -f "$PROJECT/.kanban-role.conf" ] && { set -a; source "$PROJECT/.kanban-role.conf"; set +a; }
LOCK="$PROJECT/.hermes/kanban-loop.lock"

# ---------- 模式 prompt: 只输出 codex 提示词 (模式 A) ----------
if [ "$MODE" = "prompt" ]; then
  export KRT_TASK="$TASK" KRT_BOARD="$BOARD" KRT_PROJECT="$PROJECT" KRT_TEST_CMD="${TEST_CMD:-无}"
  for r in plan dev tester review; do
    echo "# ===== $r 阶段 (codex exec 提示词) ====="; echo
    python3 - "$HERE/prompts/$r.md" <<'PY'
import os, sys
t = open(sys.argv[1], encoding="utf-8").read()
for k in ("TASK", "BOARD", "PROJECT", "TEST_CMD"):
    t = t.replace("@" + k + "@", os.environ.get("KRT_" + k, ""))
print(t)
PY
    echo
  done
  exit 0
fi

cd "$PROJECT"

# ---------- 前置检查 ----------
[ -n "$(git status --porcelain)" ] && { echo "❌ git 工作区不干净, 先处理未提交改动" >&2; git status --short | head -20 >&2; exit 1; }

SLUG="$(printf '%s' "$TASK" | tr '[:upper:]' '[:lower:]' \
  | sed 's/[^a-z0-9]/-/g; s/-\+/-/g; s/^-//; s/-$//' | cut -c1-40)"
[ -n "$SLUG" ] || SLUG="task"
BRANCH="loop/${SLUG}"
NEXT_NUM="$(python3 -c "
import glob, os
files = glob.glob('docs/plans/[0-9][0-9][0-9].*.md')
mx = 0
for f in files:
    try: mx = max(mx, int(os.path.basename(f)[:3]))
    except ValueError: pass
print(f'{mx+1:03d}')")"
PLAN_PATH="docs/plans/${NEXT_NUM}.${SLUG}.md"

# ---------- 单流水线锁 ----------
if [ -f "$LOCK" ]; then
  if [ "$FORCE" = "1" ]; then rm -f "$LOCK"
  else
    AGE=$(( $(date +%s) - $(stat -c %Y "$LOCK") ))
    if [ "$AGE" -gt "${LOCK_STALE_AFTER:-21600}" ]; then rm -f "$LOCK"
    else echo "❌ 已有流水线在运行 (lock age ${AGE}s), 等它结束或 --force" >&2; exit 1; fi
  fi
fi
mkdir -p "$PROJECT/.hermes"
echo "$SLUG $(date +%s)" > "$LOCK"
trap 'rm -f "$LOCK"' EXIT

# ---------- 分支 ----------
git checkout -b "$BRANCH" >/dev/null 2>&1 || git checkout "$BRANCH" >/dev/null 2>&1
echo "分支: $BRANCH   计划: $PLAN_PATH   看板: $BOARD"

# ---------- 卡 body 模板 ----------
HARD_CMDS=""
[ -n "${TEST_CMD:-}" ] && HARD_CMDS="硬性验证命令(调度者钉死, 必须全部字面执行且 exit 0, 不得修改/重解释/跳过):
${TEST_CMD}

"

PLAN_BODY="任务: ${TASK}

分支: ${BRANCH}
计划文件: ${PLAN_PATH} (按此编号与路径写入 docs/plans/)
轮次: 1

${HARD_CMDS}角色: 计划。分析任务→撰写实施计划(含「角色、职责与交接」章节)→
验收标准只写构建/单元测试/路由测试可验证的内容(不写 e2e/数据库/浏览器验收)→
提交后以 metadata.plan_path 交付。"
DEV_BODY="任务: ${TASK}

分支: ${BRANCH}
计划路径: 以父卡(计划卡) metadata.plan_path 为准, 默认 ${PLAN_PATH}
轮次: 1

角色: 开发。按计划实现并提交, 跑默认验证(不访问数据库)。"
TEST_BODY="任务: ${TASK}

分支: ${BRANCH}
计划路径: ${PLAN_PATH}
轮次: 1

${HARD_CMDS}角色: 测试。逐条验证验收标准, 硬性命令字面执行;
不执行 e2e/数据库集成测试(无授权环境), 构建+单测全过即通过;
失败输出 test-report.json 到 \$HERMES_KANBAN_WORKSPACE/.hermes/loop/, 评论失败报告并建「修复 #N」卡。"
REVIEW_BODY="任务: ${TASK}

分支: ${BRANCH}
计划路径: ${PLAN_PATH}
轮次: 1

角色: 终审(评审角色, 最终验收)。只读审查开发 diff、测试证据与残余风险,
抽查 1-3 条关键命令; 通过 → 评论交付清单(分支/merge 命令/提交/计划路径)并 complete;
不通过 → 建「修复 #N」卡(回 dev)或「计划修订 #N」卡(回 plan), 轮次上限 3。"

# ---------- 建卡 ----------
KB() { hermes kanban --board "$BOARD" "$@"; }
T1="$(KB create "T1 计划: ${TASK}" --assignee plan --body "$PLAN_BODY" \
      --workspace "dir:$PROJECT" --max-runtime 40m --json \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
T2="$(KB create "T2 开发: ${TASK}" --assignee dev --body "$DEV_BODY" \
      --parent "$T1" --workspace "dir:$PROJECT" --max-runtime 40m --json \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
if [ "${PLAN_APPROVAL:-auto}" = "manual" ]; then
  KB block "$T2" "计划审批: 人工确认计划后 unblock 放行开发" >/dev/null
  echo "    开发卡已阻塞, 等人工: hermes kanban --board $BOARD unblock $T2"
fi
T3="$(KB create "T3 测试: ${TASK}" --assignee tester --body "$TEST_BODY" \
      --parent "$T2" --workspace "dir:$PROJECT" --max-runtime 40m --json \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
T4="$(KB create "T4 终审: ${TASK}" --assignee review --body "$REVIEW_BODY" \
      --parent "$T3" --workspace "dir:$PROJECT" --max-runtime 40m --json \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"

echo "已建卡链: $T1 → $T2 → $T3 → $T4"
echo "盯板: hermes kanban --board $BOARD watch"

# ---------- 等待模式 ----------
[ "$NO_WAIT" = "1" ] && exit 0
DEADLINE=$(( $(date +%s) + ${WAIT_MAX:-0} * 60 ))
while :; do
  st="$(KB show "$T4" --json 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["task"]["status"])' || echo unknown)"
  case "$st" in
    done)     echo "✅ 终审通过: $T4"; echo "merge: git checkout main && git merge $BRANCH"; break ;;
    blocked)  echo "⛔ 终审卡阻塞: $T4 — 查看: hermes kanban --board $BOARD show $T4"; break ;;
    archived) echo "⛔ 终审卡已归档: $T4"; break ;;
  esac
  if [ ${WAIT_MAX:-0} -gt 0 ] && [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "⏱ 等待超时, 看板仍在运行"; break
  fi
  sleep 30
done
