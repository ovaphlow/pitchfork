#!/usr/bin/env bash
#
# agent-loop.sh — 计划(terra) → 编码(deepseek) → 测试(luna) → 评审(luna) 自动化循环
#
# 用法:
#   scripts/agent-loop.sh "任务描述" [最大迭代次数]
#
# 环境变量（均可覆盖）:
#   PLAN_PROFILE=terra      PLAN_PROFILE=deepseek  TEST_PROFILE=luna  REVIEW_PROFILE=luna
#   SANDBOX=workspace-write  codex 沙箱模式
#   MAX_ITER=5               最大迭代次数（评审不通过时回炉上限）
#   TEST_CMD=                确定性验收命令（可选）。设置后脚本会在编码阶段后自行执行，
#                            退出码非 0 直接把失败输出回传给 deepseek 重试，不经过 luna。
#   PLAN_DIR=docs/plans      计划文件目录（沿用仓库既有编号约定 NNN.<slug>.md）
#
# 循环结构（每轮迭代）:
#   1. 计划   terra  : 首个迭代生成/更新 docs/plans/NNN.<slug>.md（含验收口径）
#   2. 编码   deepseek: 按计划实现，只写文件不做 git 操作（沙箱内 .git 只读）
#   3. 闸门   TEST_CMD（若设置）: 脚本自己跑真实命令，失败直接回炉
#   4. 测试   luna   : 运行计划授权的测试，写 .hermes/loop/<ts>/test-report.json
#   5. 评审   luna   : 对照计划 diff 审查，写 .hermes/loop/<ts>/review.json
#   6. 判定   评审 approved=true 且测试 passed=true → 循环结束；否则把 findings
#              回传给 deepseek 进入下一轮，直到 MAX_ITER。
#
# 说明:
#   - git 提交由脚本在沙箱外执行（codex workspace-write 沙箱把 .git 设为只读，
#     agent 无法自行 commit）；agent 只写文件。
#   - 闸门全部基于文件/命令结果，不信任任何模型的文字自报。
#   - 脚本不自动 merge、不 push；完成后由你决定是否合并 loop 分支。
#   - 默认验证遵循 AGENTS.md：只跑不访问数据库的单元测试/编译；集成测试需另行授权。
#
set -euo pipefail

# ---------------------------------------------------------------- 配置
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO"

TASK="${1:?用法: scripts/agent-loop.sh \"任务描述\" [最大迭代次数]}"
MAX_ITER="${2:-${MAX_ITER:-5}}"

PLAN_PROFILE="${PLAN_PROFILE:-terra}"
CODE_PROFILE="${CODE_PROFILE:-deepseek}"
TEST_PROFILE="${TEST_PROFILE:-luna}"
REVIEW_PROFILE="${REVIEW_PROFILE:-luna}"
SANDBOX="${SANDBOX:-workspace-write}"
TEST_CMD="${TEST_CMD:-}"
PLAN_DIR="${PLAN_DIR:-docs/plans}"

BASE_BRANCH="$(git symbolic-ref --short -q HEAD || echo main)"
TS="$(date +%Y%m%d-%H%M%S)"
SLUG="$(printf '%s' "$TASK" | tr '[:upper:]' '[:lower:]' \
  | sed 's/[^a-z0-9]/-/g; s/-\+/-/g; s/^-//; s/-$//' | cut -c1-40)"
[ -n "$SLUG" ] || SLUG="task"
BRANCH="loop/${SLUG}-${TS}"
RUN_DIR="$REPO/.hermes/loop/${TS}"
mkdir -p "$RUN_DIR" "$REPO/$PLAN_DIR"

# 计划编号：取 docs/plans 现有最大 NNN + 1
next_plan_num() {
  local max=0 n f
  for f in "$REPO/$PLAN_DIR"/[0-9][0-9][0-9].*.md; do
    [ -e "$f" ] || continue
    n=$(basename "$f" | cut -c1-3)
    n=$((10#$n))
    [ "$n" -gt "$max" ] && max=$n
  done
  printf '%03d' $((max + 1))
}
PLAN_NUM="$(next_plan_num)"
PLAN_PATH="$REPO/$PLAN_DIR/${PLAN_NUM}.${SLUG}.md"
REPORT_PATH="$RUN_DIR/test-report.json"
REVIEW_PATH="$RUN_DIR/review.json"
FEEDBACK_FILE="$RUN_DIR/feedback.txt"

# ---------------------------------------------------------------- 工具
log()  { printf '\n===== %s =====\n' "$*"; }
jget() { python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print(d.get(sys.argv[2],''))" "$1" "$2" 2>/dev/null || true; }
jbool(){ python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print(str(bool(d.get(sys.argv[2],False))).lower())" "$1" "$2" 2>/dev/null || echo false; }

run_codex() { # profile prompt_label logfile
  local profile="$1" label="$2" logf="$3"; shift 3
  log "codex[$profile] $label"
  if ! codex exec -p "$profile" --sandbox "$SANDBOX" "$*" >"$logf" 2>&1; then
    log "[$label] codex 退出码非 0，最后 20 行输出:"
    tail -n 20 "$logf" || true
    return 1
  fi
  tail -n 3 "$logf" || true
  return 0
}

# 脚本在沙箱外统一提交（codex 沙箱内 .git 只读）。返回 0=已提交；1=无改动。
git_commit_all() {
  local msg="$1"
  git add -A
  if git diff --cached --quiet; then
    log "无待提交改动（$msg）"
    return 1
  fi
  git commit -q -m "$msg"
  log "已提交: $(git log -1 --oneline)"
  return 0
}

# ---------------------------------------------------------------- 各阶段 prompt
stage_plan() {
  local prompt
  prompt=$(cat <<EOF
你是【计划】角色（terra）。仓库：$REPO，分支：$BRANCH。

任务：$TASK

产出：撰写实施计划并写入 $PLAN_PATH（Markdown）。若该文件已存在则更新它。

计划必须包含「角色、职责与交接」章节，逐项落实：
- 参与角色：计划(terra) / 开发(deepseek) / 测试(luna) / 评审(luna)
- 逐项分配实现、测试、执行与验收
- 各角色可修改的文件范围
- 测试命令、环境、数据库授权与数据清理要求（默认只允许不访问数据库的单元测试与编译；集成测试需另行授权）
- 交付包、缺陷回传格式、最终状态更新者

并包含：目标与范围、可验证的验收口径、小步实施步骤、涉及文件清单、测试清单、风险。
只写这一个计划文件，不要改动其他任何文件，不要执行任何 git 操作（脚本负责提交）。
最后用一句话报告计划路径与验收口径。
EOF
)
  run_codex "$PLAN_PROFILE" "计划撰写" "$RUN_DIR/plan.log" "$prompt"
  [ -s "$PLAN_PATH" ] || { log "计划文件未生成: $PLAN_PATH"; return 1; }
}

stage_code() { # iteration
  local iter="$1" prompt feedback=""
  if [ -s "$FEEDBACK_FILE" ]; then
    feedback="上一轮评审/测试/闸门未通过，你必须逐条修复以下问题后再提交：\n$(cat "$FEEDBACK_FILE")\n"
  fi
  prompt=$(cat <<EOF
你是【开发】角色（deepseek）。仓库：$REPO，当前分支：$BRANCH，基线分支：$BASE_BRANCH。

实施计划：$PLAN_PATH
只实现该计划，不要自行扩展范围；遵循仓库 AGENTS.md 与子目录 AGENTS.md 的约定
（jOOQ、迁移号段、API 格式、业务枚举中文值、路由格式等）。

${feedback}要求：
- 严格按计划的「角色、职责与交接」与验收口径实现，只修改计划允许的文件。
- 不要执行任何 git 操作（沙箱内 .git 只读，脚本负责统一提交）。
- 禁止夹带无关重构、依赖升级或格式化。
- 完成后运行计划指定的默认验证（不访问数据库的单元测试/编译），报告真实命令与输出。
- 禁止：改 secrets、启动/停止服务、连数据库、提交 .env、改计划外的模块。
输出：改动文件清单、验证命令与结果、已知风险。
EOF
)
  run_codex "$CODE_PROFILE" "编码实现(迭代 $iter)" "$RUN_DIR/code-$iter.log" "$prompt"
}

stage_test() { # iteration
  local iter="$1" prompt
  prompt=$(cat <<EOF
你是【测试】角色（luna）。仓库：$REPO，分支：$BRANCH。

实施计划：$PLAN_PATH
按计划运行测试（默认只运行不访问数据库的单元测试与编译；未经授权不得连接数据库、启动服务）。
把结构化结果写入 $REPORT_PATH，JSON 格式：
{"passed": true|false, "summary": "...", "failures": [{"name": "...", "error": "..."}]}

不得修改生产代码、Shared API、UI、单元测试或路由测试。只允许新增集成测试/fixture（若计划授权）与写报告文件。
输出：执行过的命令与 exit code、报告文件路径。
EOF
)
  run_codex "$TEST_PROFILE" "测试执行" "$RUN_DIR/test-$iter.log" "$prompt"
  [ -s "$REPORT_PATH" ] || { log "测试报告未生成: $REPORT_PATH"; return 1; }
  if [ "$(jbool "$REPORT_PATH" passed)" != "true" ]; then
    log "测试未通过（luna 报告），失败明细:"
    python3 -c "import json;d=json.load(open('$REPORT_PATH'));[print('-',f.get('name'),':',f.get('error','')[:300]) for f in d.get('failures',[])]" || true
    {
      echo "【测试报告未通过】$(jget "$REPORT_PATH" summary)"
      python3 -c "import json;d=json.load(open('$REPORT_PATH'));[print('FAIL:',f.get('name'),'|',f.get('error','')[:500]) for f in d.get('failures',[])]" 2>/dev/null || true
    } > "$FEEDBACK_FILE"
    return 1
  fi
}

stage_review() { # iteration
  local iter="$1" prompt
  prompt=$(cat <<EOF
你是【评审】角色（luna）。仓库：$REPO，分支：$BRANCH，基线：$BASE_BRANCH。

实施计划：$PLAN_PATH
测试报告：$REPORT_PATH
用 git diff $BASE_BRANCH...HEAD 审查全部开发改动，对照计划的验收口径独立判断
（构建/测试通过不等于验收通过）。不代替开发修改代码。

把评审结论写入 $REVIEW_PATH，JSON 格式：
{"approved": true|false, "verdict": "approve|changes-required", "summary": "...",
 "findings": [{"severity": "blocking|minor", "file": "...", "line": 0, "issue": "..."}]}
阻断项必须 severity=blocking 且 approved=false。
输出：结论文件路径与一句话结论。
EOF
)
  run_codex "$REVIEW_PROFILE" "评审" "$RUN_DIR/review-$iter.log" "$prompt"
  [ -s "$REVIEW_PATH" ] || { log "评审结论未生成: $REVIEW_PATH"; return 1; }
  if [ "$(jbool "$REVIEW_PATH" approved)" != "true" ]; then
    log "评审未通过，阻断项:"
    python3 -c "import json;d=json.load(open('$REVIEW_PATH'));[print('-',f.get('severity'),f.get('file'),':',f.get('issue','')[:300]) for f in d.get('findings',[])]" || true
    {
      echo "【评审未通过】$(jget "$REVIEW_PATH" summary)"
      python3 -c "import json;d=json.load(open('$REVIEW_PATH'));[print('FINDING:',f.get('severity'),'|',f.get('file'),'|',f.get('issue','')[:500]) for f in d.get('findings',[])]" 2>/dev/null || true
    } > "$FEEDBACK_FILE"
    return 1
  fi
}

# ---------------------------------------------------------------- 主循环
log "任务: $TASK"
log "分支: $BRANCH (基线 $BASE_BRANCH)  计划: $PLAN_PATH  状态目录: $RUN_DIR"
git checkout -b "$BRANCH" >/dev/null 2>&1 || git checkout "$BRANCH" >/dev/null 2>&1

: > "$FEEDBACK_FILE"
approved=0
for ((i = 1; i <= MAX_ITER; i++)); do
  log "迭代 $i / $MAX_ITER"

  if [ "$i" -eq 1 ]; then
    stage_plan || exit 1
    git_commit_all "docs(plan): $SLUG" || exit 1
  else
    log "沿用计划: $PLAN_PATH（上轮反馈已注入编码 prompt）"
  fi

  stage_code "$i" || exit 1
  if ! git_commit_all "feat(loop): $SLUG (迭代 $i)"; then
    log "编码阶段没有产生任何改动，回炉"
    echo "【编码未产生任何改动】请对照计划 $PLAN_PATH 实际实现并写入文件。" > "$FEEDBACK_FILE"
    continue
  fi

  # 确定性闸门：脚本自己跑真实命令
  if [ -n "$TEST_CMD" ]; then
    log "确定性闸门: $TEST_CMD"
    if ! bash -c "$TEST_CMD" >"$RUN_DIR/gate-$i.log" 2>&1; then
      log "闸门失败，回炉（tail）:"
      tail -n 15 "$RUN_DIR/gate-$i.log" || true
      { echo "【确定性闸门未通过】$TEST_CMD"; tail -n 40 "$RUN_DIR/gate-$i.log"; } > "$FEEDBACK_FILE"
      continue
    fi
    log "闸门通过"
  fi

  stage_test "$i"   || continue
  git_commit_all "test(loop): $SLUG (迭代 $i)" || true   # luna 新增的测试/fixture 一并入库
  stage_review "$i" || continue

  approved=1
  break
done

log "循环结束"
if [ "$approved" -eq 1 ]; then
  log "✅ 验收通过（测试 passed + 评审 approved）: $(jget "$REVIEW_PATH" summary)"
else
  log "❌ 达到最大迭代次数 $MAX_ITER，仍未通过。评审结论: $(jget "$REVIEW_PATH" summary)"
  log "最后反馈（已注入下一轮编码 prompt）:"
  cat "$FEEDBACK_FILE" 2>/dev/null || true
fi

log "交付摘要"
printf '  分支:       %s\n' "$BRANCH"
printf '  计划:       %s\n' "$PLAN_PATH"
printf '  测试报告:   %s\n' "$REPORT_PATH"
printf '  评审结论:   %s\n' "$REVIEW_PATH"
printf '  阶段日志:   %s\n' "$RUN_DIR"
printf '  合并请自行: git checkout %s && git merge --no-ff %s\n' "$BASE_BRANCH" "$BRANCH"

exit $([ "$approved" -eq 1 ] && echo 0 || echo 1)
