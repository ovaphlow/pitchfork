#!/usr/bin/env bash
#
# kanban-loop.sh — 在 pitchfork 看板上创建「计划→开发→测试→评审」四角色卡片链
#
# 用法:
#   scripts/kanban-loop.sh "任务描述" [--no-wait] [--force]
#
# 环境变量:
#   TEST_CMD="..."        调度者钉死的硬性验证命令（多行用换行分隔），会嵌入计划卡与
#                         测试卡 body；测试 worker 必须全部字面执行且 exit 0，
#                         不得修改/重解释。设置后流水线有确定性闸门（推荐）。
#   PLAN_APPROVAL=manual  计划完成后开发卡保持 blocked，需人工 unblock 才放行
#                         （默认 auto 自动放行）
#   WAIT_MAX=分钟         等待流水线结束的最长分钟数（默认 0=无限；--no-wait 跳过）
#   LOCK_STALE_AFTER=秒   锁过期自动清理阈值（默认 21600=6h）
#
# 流程:
#   T1 计划(plan) → T2 开发(dev) → T3 测试(tester) → T4 评审(review)
#   评审不通过时由评审 worker 自动创建下一轮卡片（计划修订→修复/回滚→测试→评审），
#   直到通过或达到轮次上限(3)。卡片共享 dir:/home/ovaphlow/pitchfork 工作区与
#   loop/<slug> 分支，阶段间通过父卡依赖串行执行。
#
# 并发保护: 默认等待流水线结束并持有锁（.hermes/kanban-loop.lock），期间拒绝
#   创建第二条流水线（共享工作区互踩防护）；--no-wait 立即返回并释放锁（不保护）。
#
# 前置条件:
#   - git 工作区必须干净（保护已有未提交改动）
#   - 角色 profile 已创建: plan / dev / tester / review
#
set -euo pipefail

REPO="/home/ovaphlow/pitchfork"
BOARD="${BOARD:-pitchfork}"
LOCK="$REPO/.hermes/kanban-loop.lock"

NO_WAIT=0
FORCE=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --no-wait) NO_WAIT=1 ;;
    --force)   FORCE=1 ;;
    *)         ARGS+=("$a") ;;
  esac
done
TASK="${ARGS[0]:-}"
[ -n "$TASK" ] || { echo "用法: scripts/kanban-loop.sh \"任务描述\" [--no-wait] [--force]" >&2; exit 1; }

mkdir -p "$REPO/.hermes"
cd "$REPO"

# 1. 工作区必须干净
if [ -n "$(git status --porcelain)" ]; then
  echo "❌ git 工作区不干净，先处理未提交改动再跑流水线" >&2
  git status --short | head -20 >&2
  exit 1
fi

# 2. slug / 分支 / 计划编号
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

# 3. 单流水线锁（共享工作区互踩防护；建卡前获取，关闭并发竞态）
if [ -f "$LOCK" ]; then
  if [ "$FORCE" = "1" ]; then
    echo "⚠ --force: 强制覆盖已有锁"
    rm -f "$LOCK"
  else
    AGE=$(( $(date +%s) - $(stat -c %Y "$LOCK") ))
    if [ "$AGE" -gt "${LOCK_STALE_AFTER:-21600}" ]; then
      echo "⚠ 锁已过期（${AGE}s > ${LOCK_STALE_AFTER:-21600}s），自动清理"
      rm -f "$LOCK"
    else
      echo "❌ 已有流水线在运行（lock: $LOCK，age ${AGE}s）" >&2
      echo "   等它结束，或确认无活动后用 --force" >&2
      exit 1
    fi
  fi
fi
echo "$SLUG $(date +%s)" > "$LOCK"
trap 'rm -f "$LOCK"' EXIT

# 4. 创建分支并检出（worker 共用此分支）
git checkout -b "$BRANCH" >/dev/null 2>&1 || git checkout "$BRANCH" >/dev/null 2>&1
echo "分支: $BRANCH   计划: $PLAN_PATH"

# 5. 组装卡片 body
HARD_CMDS=""
if [ -n "${TEST_CMD:-}" ]; then
  HARD_CMDS="硬性验证命令（调度者钉死，必须全部字面执行且 exit 0，不得修改/重解释/跳过）:
${TEST_CMD}

"
fi
PLAN_BODY="任务: ${TASK}

分支: ${BRANCH}
计划文件: ${PLAN_PATH}（按此编号与路径写入 docs/plans/）
轮次: 1

${HARD_CMDS}按 SOUL.md 执行：撰写实施计划并提交，完成后以 metadata.plan_path 交付。"
DEV_BODY="任务: ${TASK}

分支: ${BRANCH}
计划路径: 以父卡（计划卡）metadata.plan_path 为准，默认 ${PLAN_PATH}
轮次: 1

按 SOUL.md 执行：按计划实现并提交，跑默认验证。"
TEST_BODY="任务: ${TASK}

分支: ${BRANCH}
计划路径: 以父链 metadata.plan_path 为准，默认 ${PLAN_PATH}
轮次: 1

${HARD_CMDS}按 SOUL.md 执行：按计划运行测试（硬性命令必须字面执行），输出 test-report.json 并交付。"
REVIEW_BODY="任务: ${TASK}

分支: ${BRANCH}
计划路径: 以父链 metadata.plan_path 为准，默认 ${PLAN_PATH}
轮次: 1

按 SOUL.md 执行：对照计划审查 diff 与测试证据；通过则附交付清单完成，不通过则创建下一轮卡片链。"

# 6. 创建四角色卡片链
WS="dir:${REPO}"
RT="40m"
INIT=""
if [ "${PLAN_APPROVAL:-auto}" = "manual" ]; then
  INIT="--initial-status blocked"
fi

T1=$(hermes kanban --board "$BOARD" create "计划: ${SLUG}" \
  --assignee plan --workspace "$WS" --max-runtime "$RT" --json \
  --body "$PLAN_BODY" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

T2=$(hermes kanban --board "$BOARD" create "开发: ${SLUG}" \
  --assignee dev --workspace "$WS" --max-runtime "$RT" --json \
  --parent "$T1" $INIT \
  --body "$DEV_BODY" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

T3=$(hermes kanban --board "$BOARD" create "测试: ${SLUG}" \
  --assignee tester --workspace "$WS" --max-runtime "$RT" --json \
  --parent "$T2" \
  --body "$TEST_BODY" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

T4=$(hermes kanban --board "$BOARD" create "评审: ${SLUG}" \
  --assignee review --workspace "$WS" --max-runtime "$RT" --json \
  --parent "$T3" \
  --body "$REVIEW_BODY" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

# 7. 回到 main，避免工作区停留在流水线分支
git checkout main >/dev/null 2>&1 || true

echo
echo "✅ 卡片链已创建（看板: $BOARD）:"
echo "  T1 计划   $T1  → plan"
echo "  T2 开发   $T2  → dev      (依赖 $T1)"
echo "  T3 测试   $T3  → tester   (依赖 $T2)"
echo "  T4 评审   $T4  → review   (依赖 $T3)"
if [ -n "$INIT" ]; then
  echo
  echo "计划审批模式: 开发卡保持 blocked，计划完成后人工放行:"
  echo "  hermes kanban --board $BOARD unblock $T2"
fi
[ -n "$TEST_CMD" ] && echo "确定性闸门: 已钉入 $(( $(printf '%s' "$TEST_CMD" | grep -c .) )) 条硬性命令"

# 8. 持锁等待流水线结束（默认；--no-wait 立即返回，trap 统一释放锁）
if [ "$NO_WAIT" = "1" ]; then
  echo "--no-wait: 立即返回（锁已释放，并发保护失效，请注意同一仓库只跑一条流水线）"
  exit 0
fi

WAIT_MAX="${WAIT_MAX:-0}"
waited=0
status=""
while true; do
  status=$(hermes kanban --board "$BOARD" show "$T4" --json 2>/dev/null \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['task']['status'])" 2>/dev/null || echo "")
  case "$status" in
    done|blocked|archived) break ;;
    "") echo "⚠ 无法读取评审卡状态，终止等待（流水线继续后台运行）"; break ;;
  esac
  sleep 30
  waited=$((waited + 30))
  if [ "$WAIT_MAX" -gt 0 ] && [ "$waited" -ge $((WAIT_MAX * 60)) ]; then
    echo "⚠ 等待超时（${WAIT_MAX}min），释放锁退出（流水线继续后台运行，锁已失去保护）"
    break
  fi
  echo "  ...流水线运行中 $((waited / 60))min ($(date +%H:%M:%S))  T1→$T1 T4→$status"
done

echo
echo "流水线结束: 评审卡 $T4 → $status"
if [ "$status" = "done" ]; then
  echo "✅ 验收通过。交付清单见评审卡评论:"
  echo "  hermes kanban --board $BOARD show $T4"
  echo "合并: git checkout main && git merge --no-ff $BRANCH"
else
  echo "⚠ 评审卡未通过，人工介入: hermes kanban --board $BOARD show $T4"
fi
exit $([ "$status" = "done" ] && echo 0 || echo 1)
