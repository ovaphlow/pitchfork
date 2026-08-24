#!/usr/bin/env bash
# 多阶段小型 Agent 编排演示：
# 需求分析 -> 任务拆解 -> 开发 -> 单元测试 -> 评审
# 全程通过 herdr CLI 控制 pi agents。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(mktemp -d "$SCRIPT_DIR/.herdr-demo-XXXXXX")"
echo "==> Demo dir: $DEMO_DIR"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# ---------- 创建工作区与多个 tab/pane ----------
log "创建工作区"
created="$(herdr workspace create --cwd "$DEMO_DIR" --label herdr-demo --no-focus)"
WS="$(printf '%s' "$created" | jq -r '.result.workspace.workspace_id')"
PANE_ANALYST="$(printf '%s' "$created" | jq -r '.result.root_pane.pane_id')"
echo "workspace=$WS root_pane=$PANE_ANALYST"

make_tab() {
  local label="$1"
  local out
  out="$(herdr tab create --workspace "$WS" --cwd "$DEMO_DIR" --label "$label" --no-focus)"
  printf '%s' "$out" | jq -r '.result.root_pane.pane_id'
}

PANE_PLANNER="$(make_tab planner)"
PANE_DEV="$(make_tab developer)"
PANE_TEST="$(make_tab test-runner)"
PANE_REVIEW="$(make_tab reviewer)"
echo "panes: analyst=$PANE_ANALYST planner=$PANE_PLANNER dev=$PANE_DEV test=$PANE_TEST review=$PANE_REVIEW"

# 确保 pane 处于可用的 shell 提示符，并进入演示目录
prepare_pane() {
  local pane="$1"
  herdr pane run "$pane" "cd '$DEMO_DIR'"
  herdr pane wait-output "$pane" --match "$DEMO_DIR" --timeout 10000 >/dev/null 2>&1 || true
  sleep 1
}

for pane in "$PANE_ANALYST" "$PANE_PLANNER" "$PANE_DEV" "$PANE_TEST" "$PANE_REVIEW"; do
  prepare_pane "$pane"
done

start_agent() {
  local name="$1" pane="$2"
  log "启动 agent: $name (pi, pane=$pane)"
  local attempt
  for attempt in 1 2 3 4 5; do
    if herdr agent start "$name" --kind pi --pane "$pane" --timeout 30000; then
      return 0
    fi
    echo "  agent start 第 $attempt 次失败，2 秒后重试..."
    sleep 2
  done
  echo "  agent start 多次重试仍失败" >&2
  return 1
}

prompt_agent() {
  local name="$1" text="$2"
  log "向 $name 下发任务"
  herdr agent prompt "$name" "$text" --wait --timeout 180000
}

read_agent() {
  local name="$1" out="$2"
  herdr agent read "$name" --source recent-unwrapped --lines 100 > "$out" || true
  echo "----- $name output (tail) -----"
  tail -40 "$out"
}

# ---------- Stage 1: 需求分析 ----------
start_agent analyst "$PANE_ANALYST"
prompt_agent analyst '请分析以下需求并写入 requirements.md：
我们想做一个极简命令行工具：传入一个名字，输出一句中文问候语，例如 "你好，张三！"。需要配套一个单元测试文件，验证 greet("张三") 返回包含 "张三" 的问候语。请用中文在 requirements.md 里写清功能、输入输出和验收标准，不要写代码。'
read_agent analyst "$DEMO_DIR/01-requirements-output.txt"

# ---------- Stage 2: 任务拆解 ----------
start_agent planner "$PANE_PLANNER"
prompt_agent planner '请阅读当前目录下的 requirements.md，把它拆解成可执行的小任务，写入 tasks.md。任务要包括：实现 demo.py、实现 test_demo.py、运行测试验证。只需列出任务清单，不要实现代码。'
read_agent planner "$DEMO_DIR/02-tasks-output.txt"

# ---------- Stage 3: 开发 ----------
start_agent developer "$PANE_DEV"
prompt_agent developer '请阅读当前目录下的 requirements.md 和 tasks.md，然后实现：
1. demo.py：提供 greet(name) 函数，返回包含该名字的中文问候语；同时支持命令行运行：python3 demo.py 张三 应输出问候语。
2. test_demo.py：使用 unittest 测试 greet("张三") 返回值包含 "张三"。
请同时创建这两个文件，不要运行测试。'
read_agent developer "$DEMO_DIR/03-dev-output.txt"

echo "===== 开发后目录内容 ====="
ls -la "$DEMO_DIR"

# ---------- Stage 4: 单元测试 ----------
log "运行单元测试 (pane=$PANE_TEST)"
herdr pane run "$PANE_TEST" "python3 test_demo.py"
herdr pane wait-output "$PANE_TEST" --regex "OK|FAILED|Traceback|Error" --timeout 60000
echo "----- test-runner pane output -----"
herdr pane read "$PANE_TEST" --source recent-unwrapped --lines 80 || true

# ---------- Stage 5: 评审 ----------
start_agent reviewer "$PANE_REVIEW"
prompt_agent reviewer '请审查当前目录下的 demo.py 和 test_demo.py，以及 requirements.md、tasks.md。把评审意见写入 review.md，内容包括：实现是否满足需求、测试是否有效、发现的问题和改进建议、最终结论。只做评审，不要修改代码。'
read_agent reviewer "$DEMO_DIR/05-review-output.txt"

echo ""
echo "===== 最终交付物 ====="
ls -la "$DEMO_DIR"
for f in requirements.md tasks.md demo.py test_demo.py review.md; do
  if [ -f "$DEMO_DIR/$f" ]; then
    echo "----- $f -----"
    sed -n '1,120p' "$DEMO_DIR/$f"
  else
    echo "!!! 缺少 $f"
  fi
done

echo ""
echo "===== 演示完成 ====="
echo "演示目录保留在: $DEMO_DIR"
