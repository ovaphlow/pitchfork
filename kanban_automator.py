#!/usr/bin/env python3
"""看板状态流转（后台模式）：cron 秒级返回，pi agent 在后台运行。

主脚本：扫看板 → 对每张待处理卡起后台 agent → 立即退出。
支持 --loop 自循环模式：每 10 分钟（可 --interval 调整）从扫描看板重新开始。
后台 agent：pi 完成文件改动 → 自己调 gh 更新看板状态。

Todo 列不处理（手动控制），从「需求」开始：
  需求 -> 「需求 agent」分析需求、形成文档
          ├─ 不分解：-> 开发
          └─ 分解：输出 JSON 计划列表 → 脚本创建 N 张子任务卡（落「计划」列，带「子任务」标签）
             → 父卡打「已分解」标签并移入「计划」列，等待全部子卡 Done 后自动 Done
             拆分规则：按层切片（后端卡/前端卡），先后端后前端，前端卡声明依赖的后端卡；契约先行，
             单卡控制在 agent 一轮可完成粒度（详见「需求」阶段 prompt）
  计划 -> 「评审 agent」评估/修订计划卡（通过或修订后都）-> 开发
  开发 -> 「开发 agent」编码实现             -> 测试
  测试 -> 「测试 agent」验证                 -> Done（子任务卡 Done 时自动 close issue）

「评审」列为预留状态（测试结束后的专门评审阶段），当前与 Todo 一样不自动处理。

每列并发限制：同一次运行中，每个 status 列最多起一个 agent。
防重复触发（任务执行状态，Issue 卡以标签代替锁文件）：
  Issue 卡以「⏳ 处理中」标签为任务执行状态。开始处理时**先摘除再添加**该标签，
  使 GitHub 时间线（LabeledEvent）记录本次运行的开始时间；扫描时标签存在即视为处理中，
  标签存在超过 LABEL_STALE_AFTER（高于外层 timeout 硬上限，仍在运行的 agent 不会被判过期）
  才视为残留并清除。agent 崩溃后残留标签最迟约 1 小时后由扫描清除并重新启动流程。
  DraftIssue 卡无标签能力，仍回退锁文件（/tmp/kanban_automator.lock）。

防死循环：测试失败由测试 agent 决定回退「需求」或「开发」（验收口径/需求问题退需求，
实现缺陷退开发），最多 MAX_TEST_FAILURES 次；超过后停止自动流转
（卡片留在测试列并加评论说明）。Issue 卡失败次数以「❌ 测试失败 N」标签记录
（代替失败状态文件，卡片上直接可见）；人工修复后运行 `--reset <card_id>`（摘除失败标签）
恢复，或直接在 GitHub 上移除失败标签。
父卡/子卡规则：
  - 防重复分解：「已分解」标签为主闸，subIssues 查询兜底（有子卡但缺标签时自动补打）；
    建卡由脚本完成（解析需求 agent 的 JSON），agent 不直接调 gh 建卡。
  - 子任务卡（带「子任务」标签）永不分解；测试失败只回退「开发」或「计划」
    （输出「需求」会被强制改为「计划」，避免触发父卡重新分解）。
  - 父卡聚合：每轮扫描更新「📊 子卡进度」评论（原地 PATCH 不刷屏）；全部子卡 Done 后
    父卡移 Done + 汇总评论 + close 父 issue + 摘「已分解」标签；父卡本身永不启动 agent。

标签路由：给 Issue 卡加「需要重新计划」/「需要改动」标签，扫描时先把卡片移入
「需求」/「开发」列（与处理阶段一致），再按对应阶段启动 agent，并自动清除 halt 停止状态
（父卡忽略并摘除路由标签）。
测试失败判定以输出开头的 ❌ 标记为准，避免 "0 failures"/"无需 ❌ 标记" 等通过措辞误判。
"""
import subprocess
import json
import os
import sys
import re
import time
import fcntl
import signal
from datetime import datetime, timezone

# gh 子进程输出解析依赖纯 JSON：剥离强制彩色输出的环境变量（agent 内核常带
# CLICOLOR_FORCE/GH_FORCE_TTY，会让 gh 对管道输出 ANSI 色码导致 json.loads 失败）
for _v in ("CLICOLOR_FORCE", "GH_FORCE_TTY"):
    os.environ.pop(_v, None)

PROJECT_NUMBER = "8"
OWNER = "@me"
PROJECT_ID = "PVT_kwHOAAOE884AYQef"
REPO_DIR = os.path.dirname(os.path.abspath(__file__))

CRON_LOCK = "/tmp/kanban_automator_cron.lock"
LOG_FILE = "kanban_history.txt"
LOG_OUTPUT = "/tmp/kanban_automator.log"
# DraftIssue 卡无标签能力，处理中/失败状态仍回退这两个文件；Issue 卡全部走标签
LOCK_FILE = "/tmp/kanban_automator.lock"
FAIL_STATE_FILE = "/tmp/kanban_automator_fails.json"
# resolve_option_ids() 的 field-list 结果缓存（每次实测约 102 GraphQL 点数，选项 ID 极少变动）
OPTION_CACHE_FILE = "/tmp/kanban_option_ids.json"
OPTION_CACHE_TTL = 3600  # 1 小时；缓存显示缺选项时强制刷新（人工添加后尽快生效）

IN_PROGRESS_LABEL = "⏳ 处理中"
STALE_GRACE = 60

# pi agent 调用参数
AGENT_MODEL = None
AGENT_TIMEOUT = 3600  # 单个 agent 硬上限（1 小时）；外层 timeout 与锁回收阈值均由此常量自动跟随
AGENT_CMD = ["pi", "-p", "--mode", "text"]
if AGENT_MODEL:
    AGENT_CMD += ["--model", AGENT_MODEL]
AGENT_CMD += ["--approve"]

# Issue 卡「⏳ 处理中」标签过期阈值：外层 timeout(AGENT_TIMEOUT+120) 之后再加余量。
# 仍在运行的 agent 最迟在 AGENT_TIMEOUT+120 被外层 timeout 杀死，因此该阈值保证
# 活着的 agent 永远不会被判过期（不重复启动）；崩溃残留的标签最迟约 1 小时后清除。
LABEL_STALE_AFTER = AGENT_TIMEOUT + 120 + STALE_GRACE

# Status 字段（single-select）及其选项 ID
STATUS_FIELD_ID = "PVTSSF_lAHOAAOE884AYQefzgPghq8"
OPTION_IDS = {
    "Todo": "f75ad846",
    "需求": "6ff7dcda",
    "计划": "9a427661",
    "开发": "47fc9ee4",
    "测试": "ceaca6cd",
    "评审": "6aa4326a",
    "Done": "98236657",
}
OPTION_MISSING = set()  # resolve_option_ids() 填充：Status 字段缺失的选项名

TRANSITIONS = {
    "需求": "开发",   # 需求 agent 输出分解时，实际目标改为「计划」（父卡）
    "计划": "开发",   # 评审 agent 评估/修订计划卡
    "开发": "测试",
    "测试": "Done",
}

# 需求分解为子任务
MAX_PLANS = 8                       # 单个需求最多分解的子任务数
CHILD_LABEL = "子任务"              # 子任务卡标记（需求 agent 建卡时打上）
DECOMPOSED_LABEL = "已分解"         # 父卡标记：已分解，等待子卡收尾（防重复分解主闸）
DECOMPOSED_LABEL_COLOR = "0E8A16"
PROGRESS_MARKER = "<!-- kanban-progress -->"  # 父卡进度评论标记（原地 PATCH 用）

# 标签路由：给 Issue 卡加标签即可改变自动流转方向（优先级高于列状态与 halt 停止）：
#   「需要重新计划」→ 卡片先移到「需求」列，再由需求 agent 重新分析/修订计划（目标：开发）
#   「需要改动」    → 卡片先移到「开发」列，再由开发 agent 实现改动（目标：测试）
ROUTE_LABELS = {
    "需要重新计划": "开发",
    "需要改动": "测试",
}

# 列 → 路由标签映射：卡片离开某列前摘除对应标签，防止残留标签再次触发路由
COLUMN_ROUTE_LABELS = {
    "需求": "需要重新计划",
    "开发": "需要改动",
}

# 测试失败回退到开发
# 测试失败回退开发（原 TRANSITIONS.get 会把测试失败误移到 Done）
TEST_FAIL_TARGET = "开发"

# 测试失败最大次数；超过后停止自动流转，等待人工介入
MAX_TEST_FAILURES = 2

# Issue 卡失败次数以标签记录（代替失败状态文件）：第 N 次失败加「❌ 测试失败 N」，
# 标签数即失败次数；手动摘除全部失败标签（或 --reset）即可恢复流转
FAIL_LABEL_COLOR = "B60205"
FAIL_LABELS = [f"❌ 测试失败 {i}" for i in range(1, MAX_TEST_FAILURES + 1)]

STAGE_PROMPTS = {
    "需求": "你是需求分析 agent。请分析看板卡片的需求，形成清晰的需求规格说明。" \
              "直接输出分析结果（包含背景、目标、验收标准等），不要写入文件。\n\n" \
              "验收标准只写**可以通过程序构建、单元测试/路由测试验证**的内容；" \
              "**不要包含任何 e2e 测试、数据库集成测试、浏览器验收类条目**（如「需在真实环境验证」「集成/E2E 验收」）——" \
              "本流程不做这些验证，写了只会导致卡片反复流转浪费时间。\n\n" \
              "如果评论中已有测试失败报告，请重点解决其中提出的需求/验收口径问题（如口径不清、" \
              "需求缺失或矛盾、需要需求/计划角色决策的点），输出修订后的完整需求规格说明。\n\n" \
              "如果需要把该需求分解为多个实施子任务，请在规格说明之后输出一个 JSON 代码块：\n" \
              "```json\n[{\"title\": \"子任务标题\", \"body\": \"背景说明\", \"acceptance\": \"验收标准\"}]\n```\n" \
              "拆分原则：\n" \
              "1. 按**可独立验收的切片**拆分，一张卡只覆盖一个层，不跨层：\n" \
              "   - 后端卡：Flyway 迁移 + jOOQ + Service + 路由 + 单元/路由测试，验收 = 后端编译与单测通过；\n" \
              "   - 前端卡：@pitchfork/shared/aceso 客户端导出 + 页面/组件，验收 = 前端构建与类型检查通过；\n" \
              "2. **先后端后前端**：JSON 数组按依赖顺序排列，先全部后端卡（数据模型→服务→路由），再前端卡；\n" \
              "   前端卡在 body 中声明依赖的后端卡标题，前后端按父卡规格说明中的同一 API 契约实现（契约先行，\n" \
              "   前端开发与验收不要求后端已运行）；\n" \
              "3. 每张卡控制在单个 agent 一轮可完成并自测的粒度（约 1 小时工作量）：不合并跨层改动，\n" \
              "   也不再拆碎到单函数/单组件；\n" \
              "4. 除前后端契约依赖外，子任务之间不得有代码依赖；有代码依赖就合并为一条。\n" \
              "每条验收标准必须可通过构建/单元测试/路由测试/前端构建验证；最多 8 条；\n" \
              "不需要分解就不要输出 JSON 代码块。注意：如果看板卡片是子任务（Issue 带「子任务」标签），" \
              "只输出规格说明，不要输出 JSON 代码块。",
    "计划": "你是评审 agent（计划评估）。请评估看板上的计划卡片（可能是父需求分解出的子任务，也可能是独立计划）。\n" \
              "卡片描述与评论中包含计划内容（背景、目标、验收标准）。\n\n" \
              "评估要点：\n" \
              "1. 完整性：计划是否覆盖卡片要求的全部内容；\n" \
              "2. 可实现性：是否能在现有代码库中实现；\n" \
              "3. 可验证性：每条验收标准必须可以通过程序构建、单元测试/路由测试验证，" \
              "**不要包含任何 e2e 测试、数据库集成测试、浏览器验收类条目**（本流程不做这些验证）；\n" \
              "4. 一致性：如果评论中有测试失败报告，必须解决其中提出的问题（修订验收口径或补充缺失内容）。\n\n" \
              "输出要求（直接输出，不要写入文件）：\n" \
              "- 计划没有问题：输出「✅ 评审通过」并简述结论即可；\n" \
              "- 计划需要修改：输出修订后的完整计划（背景、目标、验收标准），开头包含「❌ 需要修订」。\n" \
              "两条路径都表示评审完成，卡片随后进入开发。",
    "开发": "你是开发 agent。卡片描述或 Issue 评论中包含完整的需求规格说明（背景、目标、验收标准）。" \
              "请仔细阅读描述与评论，根据验收标准实现编码，不要询问用户。",
    "测试": "你是测试 agent。卡片描述中包含验收标准。" \
              "请逐一验证每条验收标准，运行相关测试，报告结果。\n\n" \
              "**不要执行任何 e2e 测试或数据库集成测试**（本流程无授权隔离环境，也不打算做）；" \
              "程序构建成功（编译/前端构建通过）且单元测试/路由测试全部通过，即认为测试通过。" \
              "不要因为缺少 e2e/数据库验证而判失败，也不要自行连接或操作任何 PostgreSQL。\n\n" \
              "如果任何验收标准不满足，请在输出开头包含 ❌ 标记，并说明失败原因，" \
              "同时输出一行**回退目标**（由你自行判断卡片应退回哪一列）：\n" \
              "- 代码/构建/单元测试层面的实现缺陷 → `回退目标：开发`；\n" \
              "- 验收标准本身有问题（口径不清、需求缺失或矛盾、需要需求/计划角色决策，" \
              "例如评论中提出的计划决策点）→ `回退目标：需求`。\n" \
              "失败时必须且只能输出其中一行（放在 ❌ 标记之后）；通过时不要输出。\n\n" \
              "如果这是子任务卡片（Issue 带「子任务」标签），回退目标只能输出「开发」或「计划」——" \
              "验收口径问题输出 `回退目标：计划`（由评审 agent 修订计划），不要输出「需求」。",
}


def ts():
    """终端日志时间戳前缀：`[HH:MM:SS]`。"""
    return datetime.now().strftime("[%H:%M:%S]")


# ---------- gh 工具 ----------

def gh_edit(**kwargs):
    cmd = ["gh", "project", "item-edit", PROJECT_NUMBER,
           "--owner", OWNER, "--id", str(kwargs.pop("id")),
           "--project-id", PROJECT_ID]
    for k, v in kwargs.items():
        cmd += [f"--{k.replace('_', '-')}", str(v)]
    subprocess.run(cmd, check=True)


def get_items():
    out = json.loads(subprocess.check_output([
        "gh", "project", "item-list", PROJECT_NUMBER,
        "--owner", OWNER, "--format", "json", "--limit", "100",
    ]))
    return out["items"]


# ---------- 标签工具（Issue 卡状态载体） ----------

def add_label(issue_number, label=IN_PROGRESS_LABEL):
    """给 Issue 添加标签"""
    try:
        subprocess.run(
            ["gh", "issue", "edit", str(issue_number), "--add-label", label],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"{ts()} [label] 添加标签失败: "
              f"{(e.stderr or b'').decode(errors='replace').strip() or e}", flush=True)


def remove_label(issue_number, label=IN_PROGRESS_LABEL):
    """移除 Issue 的指定标签（默认处理中标签）；标签不存在时忽略"""
    try:
        subprocess.run(
            ["gh", "issue", "edit", str(issue_number), "--remove-label", label],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"{ts()} [label] 移除标签失败: "
              f"{(e.stderr or b'').decode(errors='replace').strip() or e}", flush=True)


def ensure_label(name, color=FAIL_LABEL_COLOR, description="看板自动流转状态标签"):
    """确保标签存在（幂等；已存在则更新属性）。"""
    try:
        subprocess.run(
            ["gh", "label", "create", name, "--color", color,
             "--description", description, "--force"],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"{ts()} [label] 创建标签失败: {e}")


def get_issue_labels(issue_number):
    """获取 Issue 的标签集合（DraftIssue 无标签，返回空集）。"""
    try:
        out = json.loads(subprocess.check_output(
            ["gh", "issue", "view", str(issue_number), "--json", "labels"],
            cwd=REPO_DIR))
        return {l["name"] for l in out.get("labels", [])}
    except (subprocess.CalledProcessError, json.JSONDecodeError, KeyError):
        return set()


def remove_column_route_label(issue_number, column):
    """卡片离开某列前，摘除该列对应的路由标签（防止残留标签再次触发路由）。
    例：需求列对应「需要重新计划」，开发列对应「需要改动」；测试列无对应标签。"""
    if not issue_number:
        return
    label = COLUMN_ROUTE_LABELS.get(column)
    if label:
        remove_label(issue_number, label=label)


REPO_NAME_WITH_OWNER = None


def repo_name():
    """缓存仓库名（owner/repo），供 GraphQL 查询使用。"""
    global REPO_NAME_WITH_OWNER
    if REPO_NAME_WITH_OWNER is None:
        out = json.loads(subprocess.check_output(
            ["gh", "repo", "view", "--json", "nameWithOwner"], cwd=REPO_DIR))
        REPO_NAME_WITH_OWNER = out["nameWithOwner"]
    return REPO_NAME_WITH_OWNER


def get_label_added_at(issue_number, label):
    """查询标签最近一次被添加到该 Issue 的时间（GraphQL LabeledEvent，时间线按时间升序）。
    取最后一个匹配事件（= 最近一次 add；remove-then-add 会追加新事件，取首个会拿到
    历史旧事件导致误判过期）。失败返回 None。"""
    owner, _, repo = repo_name().partition("/")
    query = """query($owner:String!,$repo:String!,$number:Int!){
      repository(owner:$owner,name:$repo){
        issue(number:$number){
          timelineItems(itemTypes:[LABELED_EVENT], first:100){
            nodes{... on LabeledEvent{createdAt label{name}}}
          }
        }
      }
    }"""
    try:
        out = json.loads(subprocess.check_output(
            ["gh", "api", "graphql", "-f", f"query={query}",
             "-F", f"owner={owner}", "-F", f"repo={repo}",
             "-F", f"number={issue_number}"],
            cwd=REPO_DIR))
        added = None
        for n in out["data"]["repository"]["issue"]["timelineItems"]["nodes"]:
            if n.get("label", {}).get("name") == label:
                added = datetime.fromisoformat(n["createdAt"].replace("Z", "+00:00"))
        if added is not None:
            return added
    except (subprocess.CalledProcessError, json.JSONDecodeError,
            KeyError, TypeError, ValueError):
        pass
    return None


def label_is_stale(issue_number):
    """「⏳ 处理中」标签已存在，判断其是否过期（上次运行已死、标签残留）。
    阈值 LABEL_STALE_AFTER 高于外层 timeout（AGENT_TIMEOUT+120）的硬上限：
    仍在运行的 agent 永远不会被判过期（杜绝重复启动）；崩溃/被杀后残留的标签
    最迟约 1 小时后由下一轮扫描清除并重新启动流程。
    查询失败时保守返回 False（视为处理中，不误启动）。"""
    added = get_label_added_at(issue_number, IN_PROGRESS_LABEL)
    if added is None:
        return False
    age = (datetime.now(timezone.utc) - added).total_seconds()
    return age > LABEL_STALE_AFTER


# ---------- 处理中标记 / 锁（DraftIssue 卡回退） ----------

def load_lock():
    """读取锁文件。损坏时备份并返回 None——调用方必须保守处理，不得当作「全部空闲」。"""
    try:
        with open(LOCK_FILE, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}
    except json.JSONDecodeError:
        ts = datetime.now().strftime("%Y%m%d%H%M%S")
        backup = f"{LOCK_FILE}.corrupt.{ts}"
        try:
            os.replace(LOCK_FILE, backup)
        except OSError:
            backup = LOCK_FILE
        print(f"{ts()} [lock] 锁文件损坏，已备份到 {backup}", flush=True)
        return None


def save_lock(lock):
    """原子写：写 pid 后缀临时文件 + rename，读者永远读到完整 JSON，不会读到半截。"""
    tmp = f"{LOCK_FILE}.{os.getpid()}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(lock, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, LOCK_FILE)


def load_modify_save(modify):
    """flock 串行化的 读-改-写，防止并发 agent 互相覆盖锁条目（lost update）。"""
    fd = open(LOCK_FILE + ".flock", "w")
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        lock = load_lock()
        if lock is None:  # 损坏已备份，从空锁重建
            lock = {}
        result = modify(lock)
        save_lock(lock)
        return result
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        fd.close()


def mark_in_progress(card_id, original_title, issue_number=None):
    """标记处理中。Issue 卡：以「⏳ 处理中」标签为任务执行状态——
    先摘除再添加，保证每次运行都产生新的 LabeledEvent 时间戳（供过期判定），
    即使上次运行崩溃残留了旧标签也会被刷新。
    DraftIssue 卡：无标签能力，回退锁文件。"""
    if issue_number:
        remove_label(issue_number)  # 先摘：残留旧标签时刷新时间戳；无标签时忽略失败
        add_label(issue_number)
        return

    def _mark(lock):
        lock[card_id] = {
            "original_title": original_title,
            "issue_number": issue_number,
            "started_at": datetime.now().isoformat(timespec="seconds"),
            "pid": os.getpid(),
        }

    load_modify_save(_mark)


def unmark_in_progress(card_id, issue_number=None):
    """解除处理中标记。Issue 卡：摘「⏳ 处理中」标签；DraftIssue 卡：清锁文件条目。"""
    if issue_number:
        remove_label(issue_number)
        return

    def _unmark(lock):
        return lock.pop(card_id, None)

    entry = load_modify_save(_unmark)
    issue_number = entry.get("issue_number") if entry else None
    if issue_number:
        remove_label(issue_number)


def is_stale(entry):
    started = datetime.fromisoformat(entry["started_at"])
    age = (datetime.now() - started).total_seconds()
    try:
        os.kill(entry["pid"], 0)
        if age < AGENT_TIMEOUT + STALE_GRACE:
            return False
    except OSError:
        pass
    return age > STALE_GRACE


def in_progress_state(card_id, issue_number, labels):
    """判断卡片是否处理中。返回 (in_progress, fatal)。
    Issue 卡：以「⏳ 处理中」标签为任务执行状态；标签过期（上次 agent 早已被杀）时
    清除残留标签并按空闲处理。DraftIssue 卡：回退锁文件；锁文件损坏时 fatal=True，
    本轮应中止（保守，不误启动）。"""
    if issue_number:
        if IN_PROGRESS_LABEL in labels:
            if label_is_stale(issue_number):
                print(f"{ts()} 清除残留处理中标签: {card_id} 处理已超时")
                remove_label(issue_number)
                return False, False
            return True, False
        return False, False

    lock = load_lock()
    if lock is None:
        print(f"{ts()} [lock] 锁文件损坏，本轮不启动任何 agent，请人工检查 {LOCK_FILE}")
        return False, True
    entry = lock.get(card_id)
    if entry:
        if not is_stale(entry):
            return True, False
        print(f"{ts()} 清除残留标记: {card_id}")
        unmark_in_progress(card_id)
    return False, False


# ---------- 测试失败次数（防死循环；Issue 卡以标签记录） ----------

def fail_count_from_labels(labels):
    """从标签集合统计测试失败次数（「❌ 测试失败 N」标签的数量）。"""
    return sum(1 for l in FAIL_LABELS if l in labels)


def load_fail_state():
    """读取失败状态（DraftIssue 卡回退）。损坏时备份重建，不影响主流程。"""
    try:
        with open(FAIL_STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_fail_state(state):
    tmp = f"{FAIL_STATE_FILE}.{os.getpid()}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, FAIL_STATE_FILE)


def fail_state_modify(modify):
    """flock 串行化读-改-写失败状态（DraftIssue 卡回退）。"""
    fd = open(FAIL_STATE_FILE + ".flock", "w")
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        state = load_fail_state()
        result = modify(state)
        save_fail_state(state)
        return result
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        fd.close()


def record_test_failure(card_id, title, issue_number=None):
    """记录一次测试失败，返回 (fail_count, halted)。达 MAX_TEST_FAILURES 后 halted=True。
    Issue 卡：加「❌ 测试失败 N」标签（标签即计数）；DraftIssue 卡：回退失败状态文件。"""
    if issue_number:
        labels = get_issue_labels(issue_number)
        n = fail_count_from_labels(labels) + 1
        if n > MAX_TEST_FAILURES:
            n = MAX_TEST_FAILURES  # 已超限（人工加过标签等边界情况），不再叠加
        else:
            ensure_label(FAIL_LABELS[n - 1])
            add_label(issue_number, label=FAIL_LABELS[n - 1])
        return n, n >= MAX_TEST_FAILURES

    def _m(state):
        entry = state.get(card_id, {})
        entry["fail_count"] = entry.get("fail_count", 0) + 1
        entry["title"] = title
        entry["updated_at"] = datetime.now().isoformat(timespec="seconds")
        entry["halted"] = entry["fail_count"] >= MAX_TEST_FAILURES
        state[card_id] = entry
        return entry["fail_count"], entry["halted"]

    return fail_state_modify(_m)


def clear_fail_state(card_id, issue_number=None):
    """成功流转（任何正向移动）后清零失败计数。
    Issue 卡：摘除全部「❌ 测试失败 N」标签；DraftIssue 卡：清失败状态文件条目。"""
    if issue_number:
        for label in FAIL_LABELS:
            remove_label(issue_number, label=label)
        return

    def _m(state):
        state.pop(card_id, None)

    fail_state_modify(_m)


def is_halted(card_id, issue_number=None, labels=None):
    """该卡是否已因测试失败次数超限被停止自动流转。
    Issue 卡：失败标签数量达 MAX_TEST_FAILURES；DraftIssue 卡：回退失败状态文件。"""
    if issue_number:
        if labels is None:
            labels = get_issue_labels(issue_number)
        return fail_count_from_labels(labels) >= MAX_TEST_FAILURES
    state = load_fail_state()
    entry = state.get(card_id)
    return bool(entry and entry.get("halted"))


def find_issue_number(card_id):
    """根据卡片 ID 反查 Issue 编号（--reset 用）。DraftIssue 返回 None。"""
    try:
        for item in get_items():
            if item["id"] == card_id:
                content = item.get("content", {})
                if content.get("type") == "Issue":
                    return content.get("number")
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        pass
    return None


# ---------- agent / 状态 ----------

def agent_env():
    """pi 子进程环境：剔除代理变量（局域网 clash 代理会挂起流式 API 请求）；
    追加 GRADLE_OPTS 禁用构建 daemon（-D 系统属性优先级高于项目 gradle.properties 的 daemon=true）。"""
    env = {k: v for k, v in os.environ.items()
           if k.lower() not in ("http_proxy", "https_proxy", "all_proxy", "no_proxy")}
    gradle_opts = env.get("GRADLE_OPTS", "")
    env["GRADLE_OPTS"] = (gradle_opts
                          + " -Dorg.gradle.daemon=false"
                          + " -Dkotlin.compiler.execution.strategy=in-process").strip()
    return env


# 当前 pi 子进程 pid（供 SIGTERM handler 清理用）
CURRENT_PI_PID = None


def _sigterm_handler(signum, frame):
    """外层 timeout 杀 python agent 时：先清理 pi 进程组和空闲 daemon，再退出。"""
    if CURRENT_PI_PID:
        kill_group(CURRENT_PI_PID, signal.SIGKILL)
    cleanup_build_daemons()
    sys.exit(0)


def kill_group(pid, sig=signal.SIGTERM):
    """向进程组发信号（pi 以 start_new_session 启动，pid 即 pgid）。"""
    try:
        os.killpg(pid, sig)
    except (ProcessLookupError, PermissionError):
        pass


def cleanup_build_daemons():
    """清理空闲的 gradle/kotlin 守护进程（它们 setsid 脱离进程组，需单独清理；有活跃构建的保留）。"""
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "GradleDaemon|KotlinCompileDaemon"],
            text=True, stderr=subprocess.DEVNULL)
    except (subprocess.CalledProcessError, FileNotFoundError):
        return
    for pid in out.split():
        busy = False
        try:
            children = subprocess.check_output(
                ["pgrep", "-P", pid], text=True, stderr=subprocess.DEVNULL)
            busy = bool(children.split())
        except subprocess.CalledProcessError:
            busy = False  # 无子进程 = 空闲
        except OSError:
            continue
        if not busy:
            try:
                os.kill(int(pid), signal.SIGTERM)
                print(f"{ts()} [cleanup] 已清理空闲构建 daemon pid={pid}", flush=True)
            except OSError:
                pass


def run_agent(prompt, capture_output=False):
    """调用 pi agent，返回 (ok, output, timed_out)。
    pi 以独立进程组启动；结束（含超时/异常）时整组清理 + 清理空闲构建 daemon，不留 java 等子进程。"""
    global CURRENT_PI_PID
    print(f"{ts()} --- 调用 pi agent ---")
    proc = subprocess.Popen(
        AGENT_CMD + [prompt], cwd=REPO_DIR,
        stdout=subprocess.PIPE if capture_output else None,
        stderr=subprocess.STDOUT,
        text=True, env=agent_env(),
        start_new_session=True,
    )
    CURRENT_PI_PID = proc.pid
    ok = False
    timed_out = False
    partial = ""
    try:
        if capture_output:
            out, _ = proc.communicate(timeout=AGENT_TIMEOUT)
            ok = proc.returncode == 0
            partial = out or ""
        else:
            proc.wait(timeout=AGENT_TIMEOUT)
            ok = proc.returncode == 0
    except subprocess.TimeoutExpired as e:
        print(f"{ts()} [超时] agent 超过 {AGENT_TIMEOUT}s 未完成")
        timed_out = True
        if capture_output and e.output:
            partial = e.output[-4000:]
        kill_group(proc.pid, signal.SIGTERM)
        try:
            if capture_output:
                tail, _ = proc.communicate(timeout=10)
                if tail:
                    partial = (partial + tail)[-4000:]
            else:
                proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            kill_group(proc.pid, signal.SIGKILL)
            proc.wait()
    finally:
        CURRENT_PI_PID = None
        # 兜底：清理 pi 进程组残留（bash/java/编译进程）
        kill_group(proc.pid, signal.SIGKILL)
        # gradle/kotlin daemon 脱离进程组，单独清理空闲的
        cleanup_build_daemons()

    if timed_out and not capture_output:
        partial = tail_file(LOG_OUTPUT, 4000)
    return ok, partial, timed_out


def tail_file(path, max_chars):
    """读取文件末尾 max_chars 字符（超时后保留现场用）。"""
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            f.seek(max(0, size - max_chars * 4))  # 多读一些，兼容多字节字符
            data = f.read()
        return data.decode("utf-8", errors="replace")[-max_chars:]
    except OSError:
        return ""


def add_comment(issue_number, body):
    """给 Issue 添加评论"""
    try:
        subprocess.run(
            ["gh", "issue", "comment", str(issue_number), "--body", body],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"{ts()} [comment] 添加评论失败: {e}")


def append_draft_body(card_id, content_id, text, max_len=6000):
    """DraftIssue 卡：把 text 追加到卡片描述末尾（body 只能用 DI_ content_id 更新）。"""
    items = json.loads(subprocess.check_output([
        "gh", "project", "item-list", PROJECT_NUMBER,
        "--owner", OWNER, "--format", "json", "--limit", "100",
    ]))
    current_body = ""
    for item in items["items"]:
        if item["id"] == card_id:
            current_body = item.get("content", {}).get("body", "")
            break
    new_body = f"{current_body.strip()}\n\n---\n\n{text}"[-max_len:]
    gh_edit(id=content_id if content_id else card_id, body=new_body)


def log_step(title, target_status):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(f"{ts}  {title}: {target_status}\n")


# ---------- 状态选项解析 ----------

def resolve_option_ids():
    """按名称解析 Status 字段选项 ID（field-list），失败用硬编码兜底。
    返回缺失的选项名集合（如项目设置里还没加「计划」）。
    结果缓存 OPTION_CACHE_TTL 秒：field-list 每次实测约消耗 102 GraphQL 点数，
    选项 ID 极少变动，无需每轮扫描/每个 agent 运行都查；缓存里缺选项时强制刷新
    （选项可能刚被人工添加，保证添加后尽快生效）。"""
    global OPTION_IDS, OPTION_MISSING
    try:
        with open(OPTION_CACHE_FILE, encoding="utf-8") as f:
            cache = json.load(f)
        if (time.time() - cache["resolved_at"] < OPTION_CACHE_TTL
                and not cache.get("missing")):
            OPTION_IDS = cache["option_ids"]
            OPTION_MISSING = set()
            return OPTION_MISSING
    except (FileNotFoundError, json.JSONDecodeError, KeyError, TypeError):
        pass

    ids = dict(OPTION_IDS)
    missing = set()
    try:
        fields = json.loads(subprocess.check_output(
            ["gh", "project", "field-list", PROJECT_NUMBER, "--owner", OWNER,
             "--format", "json"], cwd=REPO_DIR))
        for f in fields.get("fields", []):
            if f.get("id") == STATUS_FIELD_ID:
                for opt in f.get("options", []):
                    ids[opt["name"]] = opt["id"]
    except (subprocess.CalledProcessError, json.JSONDecodeError, KeyError, TypeError):
        pass
    for name in ("Todo", "需求", "计划", "开发", "测试", "评审", "Done"):
        if name not in ids:
            missing.add(name)
    OPTION_IDS = ids
    OPTION_MISSING = missing
    # 原子写缓存
    cache = {"resolved_at": time.time(), "option_ids": ids, "missing": sorted(missing)}
    tmp = f"{OPTION_CACHE_FILE}.{os.getpid()}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, OPTION_CACHE_FILE)
    if missing:
        print(f"{ts()} [field] Status 字段缺少选项: {sorted(missing)}（请在项目设置添加）", flush=True)
    return missing


# ---------- 需求分解 / 子任务 ----------

def parse_plan_json(text):
    """从需求 agent 输出中解析计划列表（```json 围栏或纯 JSON）。
    格式不符/超限/缺字段时返回 None（调用方降级为单卡流程）。"""
    m = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    candidate = m.group(1) if m else text.strip()
    if not candidate.lstrip().startswith("["):
        return None
    try:
        plans = json.loads(candidate)
    except json.JSONDecodeError:
        return None
    if not isinstance(plans, list) or not plans or len(plans) > MAX_PLANS:
        return None
    out = []
    for p in plans:
        if not isinstance(p, dict):
            return None
        title = str(p.get("title", "")).strip()
        body = str(p.get("body", "")).strip()
        acceptance = str(p.get("acceptance", "")).strip()
        if not title or not acceptance:
            return None
        out.append({"title": title, "body": body, "acceptance": acceptance})
    return out


def split_spec_and_json(text):
    """截取规格说明部分：去掉末尾的 JSON 代码块（分解计划）。无代码块则原样返回。"""
    m = re.search(r"```(?:json)?\s*\[", text)
    if m:
        return text[:m.start()].rstrip()
    if text.lstrip().startswith("[") and text.rstrip().endswith("]"):
        return ""  # 纯 JSON 输出：无规格文本
    return text


def create_plan_children(parent_number, plans):
    """把需求 agent 的 JSON 计划列表落成子任务卡：
    gh issue create --parent 关联 → 加入看板 → 置「计划」列 → 打「子任务」标签。
    返回 [(issue_number, title)]；全部失败返回 []。"""
    if "计划" in OPTION_MISSING:
        print(f"{ts()} [plan] Status 字段缺少「计划」选项，无法创建子卡（请在项目设置添加）", flush=True)
        return []
    # 子任务标签必须先存在，否则后续 add_label 全部失败（gh 对不存在的标签报错），
    # 导致子卡缺失「子任务」标签、测试失败回退限制失效
    ensure_label(CHILD_LABEL, color="0E8A16", description="看板子任务卡标记（由需求分解生成）")
    created = []
    for p in plans:
        title = f"[#{parent_number}] {p['title']}"
        body = f"## 背景\n{p['body'] or '（由父卡需求分解生成）'}\n\n## 验收标准\n{p['acceptance']}"
        try:
            url = subprocess.check_output(
                ["gh", "issue", "create", "--parent", str(parent_number),
                 "--title", title, "--body", body],
                cwd=REPO_DIR, text=True).strip()
            number = int(url.rstrip("/").split("/")[-1])
            item_id = subprocess.check_output(
                ["gh", "project", "item-add", PROJECT_NUMBER, "--owner", OWNER,
                 "--url", url, "--format", "json", "--jq", ".id"],
                cwd=REPO_DIR, text=True).strip().strip('"')
            gh_edit(id=item_id, field_id=STATUS_FIELD_ID,
                    single_select_option_id=OPTION_IDS["计划"])
            add_label(number, label=CHILD_LABEL)
            created.append((number, title))
            print(f"{ts()} [plan] 已创建子卡 #{number} {p['title']!r}", flush=True)
        except (subprocess.CalledProcessError, ValueError) as e:
            print(f"{ts()} [plan] 创建子卡失败 {p['title']!r}: {e}", flush=True)
    return created


def get_subissue_numbers(issue_number):
    """查询 Issue 的直接子任务编号列表（GraphQL）。失败返回 None。"""
    owner, _, repo = repo_name().partition("/")
    query = """query($owner:String!,$repo:String!,$number:Int!){
      repository(owner:$owner,name:$repo){
        issue(number:$number){
          subIssues(first:50){nodes{number}}
        }
      }
    }"""
    try:
        out = json.loads(subprocess.check_output(
            ["gh", "api", "graphql", "-f", f"query={query}",
             "-F", f"owner={owner}", "-F", f"repo={repo}",
             "-F", f"number={issue_number}"],
            cwd=REPO_DIR))
        return [n["number"] for n in out["data"]["repository"]["issue"]["subIssues"]["nodes"]]
    except (subprocess.CalledProcessError, json.JSONDecodeError, KeyError, TypeError):
        return None


def update_progress_comment(issue_number, body):
    """维护父卡进度评论：带 PROGRESS_MARKER 的评论原地 PATCH（不刷屏），不存在则创建。"""
    try:
        comments = json.loads(subprocess.check_output(
            ["gh", "api", f"repos/{repo_name()}/issues/{issue_number}/comments",
             "--jq", "[.[] | {id: .id, body: .body}]"],
            cwd=REPO_DIR))
        for c in comments:
            if PROGRESS_MARKER in c.get("body", ""):
                if c["body"].strip() == body.strip():
                    return
                subprocess.run(
                    ["gh", "api", "-X", "PATCH",
                     f"repos/{repo_name()}/issues/comments/{c['id']}",
                     "-f", f"body={body}"],
                    cwd=REPO_DIR, check=True, capture_output=True)
                return
    except (subprocess.CalledProcessError, json.JSONDecodeError, KeyError):
        pass
    add_comment(issue_number, body)


def aggregate_parent(card, issue_number, labels, items_by_number):
    """父卡聚合（每轮扫描调用）：
    - 全部子卡 Done → 父卡移 Done + close 父 issue + 摘「已分解」标签 + 最终评论；
    - 否则更新「📊 子卡进度」评论（含卡住/未入板告警）。
    返回 True = 已按父卡处理（本轮跳过）；False = 非父卡（正常流程继续）。"""
    if DECOMPOSED_LABEL in labels:
        subissue_numbers = get_subissue_numbers(issue_number) or []
    else:
        subs = get_subissue_numbers(issue_number)
        if not subs:
            return False
        subissue_numbers = subs
        # 有子卡但缺「已分解」标签（异常残留）：补打，防止重复分解
        ensure_label(DECOMPOSED_LABEL, color=DECOMPOSED_LABEL_COLOR)
        add_label(issue_number, label=DECOMPOSED_LABEL)
        print(f"{ts()} 补打已分解标签: {card['title']!r}", flush=True)

    total = 0
    done = 0
    halted = []
    missing = []
    for n in subissue_numbers:
        item = items_by_number.get(n)
        if not item:
            missing.append(n)
            continue
        total += 1
        if item.get("status") == "Done":
            done += 1
        elif fail_count_from_labels(get_issue_labels(n)) >= MAX_TEST_FAILURES:
            halted.append(item["title"])

    if total == 0:
        # 已标记分解但没有任何子卡在板（建卡失败/被移出）：提示人工，不自动流转
        update_progress_comment(
            issue_number, f"⚠️ 已标记分解但板上没有子卡，请人工检查（可能建卡失败）。\n\n{PROGRESS_MARKER}")
        print(f"{ts()} 跳过(父卡异常): {card['title']!r} 无子卡在板", flush=True)
        return True

    if done == total:
        update_progress_comment(
            issue_number, f"## ✅ 全部 {total} 个子任务已完成，父卡移至 Done。\n\n{PROGRESS_MARKER}")
        gh_edit(id=card["id"], field_id=STATUS_FIELD_ID,
                single_select_option_id=OPTION_IDS["Done"])
        remove_label(issue_number, label=DECOMPOSED_LABEL)
        try:
            subprocess.run(["gh", "issue", "close", str(issue_number)],
                           cwd=REPO_DIR, check=True, capture_output=True)
            print(f"{ts()} 已关闭父 issue #{issue_number}", flush=True)
        except subprocess.CalledProcessError as e:
            print(f"{ts()} [aggregate] 关闭父 issue 失败: {e}", flush=True)
        log_step(card["title"], "Done(子任务全部完成)")
        print(f"{ts()} [aggregate] 父卡完成: {card['title']!r} -> Done", flush=True)
        return True

    parts = [f"📊 子卡进度：{done}/{total} 完成"]
    if halted:
        parts.append(f"⛔ 卡住：{'、'.join(halted)}")
    if missing:
        parts.append(f"⚠️ 未入板：{'、'.join(str(m) for m in missing)}")
    body = "\n".join(parts) + f"\n\n{PROGRESS_MARKER}"
    update_progress_comment(issue_number, body)
    print(f"{ts()} 跳过(父卡等待子任务): {card['title']!r} {done}/{total}"
          + (f" 卡住:{len(halted)}" if halted else ""), flush=True)
    return True


# ---------- 模式一：后台 agent（被主脚本 fork） ----------

def _has_fail_marker(text):
    """文本中是否存在真正的失败 ❌ 标记。
    排除「无需 ❌ 标记」「不需要 ❌」等通过措辞（agent 常写"全部通过，无需 ❌ 标记"）。"""
    for m in re.finditer("❌", text):
        ctx = text[max(0, m.start() - 8):m.start()]
        if any(neg in ctx for neg in ("无需", "不需", "不需要", "不用", "没有", "无", "不含", "no", "not")):
            continue
        return True
    return False


def is_test_failure(output):
    """判断测试 agent 输出是否为失败。
    依据：测试 prompt 要求失败时在输出开头包含 ❌；
    必须排除 "0 failures"/"未发现失败"/"无需 ❌ 标记" 等通过措辞（旧实现因 "fail" 子串误判导致死循环）。"""
    if not output:
        return False
    head = output[:2000]
    if _has_fail_marker(head):
        return True
    low = head.lower()
    # 剔除常见通过措辞
    for noise in ("0 failures", "0 failed", "0 tests failed", "not fail",
                  "未发现失败", "无失败", "没有失败", "零失败",
                  "未发现不满足", "无不满足", "没有不满足", "零不满足",
                  "0 测试失败", "无测试失败", "没有测试失败", "测试失败率"):
        low = low.replace(noise, "")
    return any(k in low for k in ["未通过", "不满足", "failed", "验收失败", "测试失败"])


def parse_fallback_target(output):
    """解析测试 agent 声明的回退目标。格式：`回退目标：需求` 或 `回退目标：开发`。
    未声明或格式不符时默认回退开发。"""
    if not output:
        return TEST_FAIL_TARGET
    m = re.search(r"回退目标\s*[:：]\s*(需求|开发)", output)
    return m.group(1) if m else TEST_FAIL_TARGET


def run_agent_mode(card_id, original_title, current, target, issue_number=None, content_id=None):
    """单张卡的 agent 执行：mark → pi → gh 更新 → unmark。
    current = 卡片当前列（决定 prompt 与处理逻辑），target = 目标列（通常 TRANSITIONS[current]）。"""
    resolve_option_ids()
    # 子任务判定（带「子任务」标签）
    is_child = False
    if issue_number:
        is_child = CHILD_LABEL in get_issue_labels(issue_number)

    # 读取卡片描述
    card_body = ""
    try:
        items = json.loads(subprocess.check_output([
            "gh", "project", "item-list", PROJECT_NUMBER,
            "--owner", OWNER, "--format", "json", "--limit", "100",
        ]))
        for item in items["items"]:
            if item["id"] == card_id:
                card_body = item.get("content", {}).get("body", "")
                break
    except Exception:
        pass

    # 读取 Issue 的 comments（如果有）
    comments = []
    if issue_number:
        try:
            comments_data = json.loads(subprocess.check_output([
                "gh", "issue", "view", str(issue_number),
                "--json", "comments",
            ], cwd=REPO_DIR))
            comments = comments_data.get("comments", [])
        except Exception:
            pass

    prompt = STAGE_PROMPTS[current]
    if current == "需求" and is_child:
        prompt += "\n\n注意：这是子任务卡片，只输出规格说明，不要输出 JSON 分解块。"
    prompt += f"\n\n看板标题：{original_title}"
    if card_body:
        prompt += f"\n\n看板描述：\n{card_body}"
    if comments:
        prompt += "\n\n---\n\nIssue 评论记录："
        for c in comments[-5:]:  # 最近5条评论
            author = c.get("author", {}).get("login", "unknown")
            body = c.get("body", "")
            prompt += f"\n\n**{author}**: {body[:8000]}"

    print(f"{ts()} [agent] 开始: {original_title!r} ({current} -> {target})")
    mark_in_progress(card_id, original_title, issue_number)

    # 需求/计划/测试阶段捕获输出
    capture = current in ("需求", "计划", "测试")
    ok, output, timed_out = run_agent(prompt, capture_output=capture)

    # 检查测试是否失败（通过输出中的标志）
    test_failed = False
    failure_reason = None
    if ok and current == "测试" and output and is_test_failure(output):
        test_failed = True
        failure_reason = output.strip()[-4000:] if len(output) > 4000 else output.strip()
        print(f"{ts()} [agent] 测试未通过，将回退到开发")

    if ok and not test_failed:
        target_status = target

        # 需求阶段：写规格说明；若输出 JSON 计划列表且非子任务 → 分解为子任务卡
        if current == "需求" and output:
            plan_output = output.strip()
            spec_text = split_spec_and_json(plan_output)
            plans = None if is_child else parse_plan_json(plan_output)
            if plans and issue_number:
                if spec_text:
                    add_comment(issue_number, spec_text)
                    print(f"{ts()} [agent] 已追加需求规格到 Issue 评论: {len(spec_text)} chars")
                children = create_plan_children(issue_number, plans)
                if children:
                    ensure_label(DECOMPOSED_LABEL, color=DECOMPOSED_LABEL_COLOR)
                    add_label(issue_number, label=DECOMPOSED_LABEL)
                    listing = "\n".join(f"- [#{num}] {t}" for num, t in children)
                    add_comment(issue_number, f"## 📋 已分解为 {len(children)} 个子任务\n\n{listing}")
                    update_progress_comment(
                        issue_number, f"📊 子卡进度：0/{len(children)} 完成\n\n{PROGRESS_MARKER}")
                    target_status = "计划"  # 父卡移入计划列等待子卡收尾
                    log_step(original_title, f"计划(分解为{len(children)}个子任务)")
                    print(f"{ts()} [agent] 已分解: {original_title!r} -> {len(children)} 张子卡")
                else:
                    add_comment(issue_number, "⚠️ 需求分解建卡失败（0 张子卡），按单卡流程继续。")
            elif spec_text:
                if issue_number:
                    add_comment(issue_number, spec_text)
                    print(f"{ts()} [agent] 已追加计划到 Issue 评论: {len(spec_text)} chars")
                else:
                    try:
                        append_draft_body(card_id, content_id, spec_text)
                        print(f"{ts()} [agent] 已追加计划到描述: {len(spec_text)} chars")
                    except subprocess.CalledProcessError as e:
                        print(f"{ts()} [agent] 写入卡片描述失败: {e}")

        # 计划阶段（评审 agent）：把评审结论写回卡片（通过/修订记录）
        if current == "计划" and output:
            verdict = "通过" if "需要修订" not in output[:200] else "修订"
            record = f"## 评审记录（{verdict}）\n\n{output.strip()}"
            if issue_number:
                add_comment(issue_number, record)
                print(f"{ts()} [agent] 已写评审记录到 Issue 评论: {len(record)} chars")
            else:
                try:
                    append_draft_body(card_id, content_id, record)
                    print(f"{ts()} [agent] 已写评审记录到描述: {len(record)} chars")
                except subprocess.CalledProcessError as e:
                    print(f"{ts()} [agent] 写入评审记录失败: {e}")

        # 移状态前先摘「处理中」标签：卡片不带标签进入目标列。
        # 标签语义 = 当前列里正在被处理的那张卡；锁文件条目最后再清（防误重启）
        if issue_number:
            remove_label(issue_number)
        # 移走前摘除当前列对应的路由标签（如需求列的「需要重新计划」），防止残留标签再次触发路由
        remove_column_route_label(issue_number, current)

        gh_edit(id=card_id, field_id=STATUS_FIELD_ID,
                single_select_option_id=OPTION_IDS[target_status])
        clear_fail_state(card_id, issue_number)  # 正向流转成功，清零失败计数
        if target_status == "Done" and is_child:
            # 子任务完成 → close 子 issue
            try:
                subprocess.run(["gh", "issue", "close", str(issue_number)],
                               cwd=REPO_DIR, check=True, capture_output=True)
                print(f"{ts()} [agent] 已关闭子任务 issue #{issue_number}")
            except subprocess.CalledProcessError as e:
                print(f"{ts()} [agent] 关闭子任务 issue 失败: {e}")
        log_step(original_title, target_status)
        print(f"{ts()} [agent] 完成: {original_title!r} -> {target_status}")
    elif test_failed:
        # 测试失败：记录失败次数；由测试 agent 声明回退目标（需求/开发），未超限则回退，超限则停止自动流转
        fail_count, halted = record_test_failure(card_id, original_title, issue_number)
        fail_target = parse_fallback_target(output or "")
        if is_child and fail_target == "需求":
            # 子卡不回需求（防止触发父卡重新分解），改由评审 agent 修订计划
            fail_target = "计划"
            print(f"{ts()} [agent] 子卡回退目标「需求」已强制改为「计划」")
        fail_body = f"## 测试失败报告（第 {fail_count} 次）\n\n**失败时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n**失败原因**:\n\n{failure_reason}"

        # 失败报告：Issue 卡写评论（body 无法用 item-edit 更新），DraftIssue 卡追加到描述
        if issue_number:
            add_comment(issue_number, fail_body)
            print(f"{ts()} [agent] 已添加失败评论到 Issue")
        else:
            try:
                append_draft_body(card_id, content_id, fail_body)
                print(f"{ts()} [agent] 已记录失败原因到描述")
            except Exception as e:
                print(f"{ts()} [agent] 更新描述失败: {e}")

        # 回退/停止前先摘标签（卡片不带标签进入目标列或停驻测试列）
        if issue_number:
            remove_label(issue_number)
        # 移走前同样摘除当前列对应的路由标签
        remove_column_route_label(issue_number, current)

        if halted:
            # 超过最大重试次数：停止自动流转，卡片留在当前列（测试），等待人工介入
            halt_body = (f"## ⛔ 已停止自动流转\n\n该卡测试失败已达 **{MAX_TEST_FAILURES} 次**，"
                         f"为避免死循环已停止自动流转。请人工介入：\n\n"
                         f"1. 检查最近失败报告，修复问题或调整验收口径（数据库类验收项在无授权环境时不得判失败）；\n"
                         f"2. 恢复自动流转：运行 `python3 kanban_automator.py --reset {card_id}`"
                         f"（会摘除本卡全部「❌ 测试失败」标签），"
                         f"或直接在 GitHub 上移除该卡全部「❌ 测试失败」标签，\n"
                         f"    或给卡片添加标签「需要重新计划」（退回需求重新计划）或「需要改动」（退回开发实现），\n"
                         f"    下一轮扫描会自动按标签路由处理；\n"
                         f"3. 也可手动把卡片移到「计划」列（评审 agent 修订计划）或「开发」/「需求」列继续。")
            if issue_number:
                add_comment(issue_number, halt_body)
            else:
                try:
                    append_draft_body(card_id, content_id, halt_body)
                except Exception as e:
                    print(f"{ts()} [agent] 写入停止流转说明失败: {e}")
            log_step(original_title, f"停止流转({fail_count}次测试失败)")
            print(f"{ts()} [agent] 已停止自动流转: {original_title!r}（测试失败 {fail_count}/{MAX_TEST_FAILURES} 次，请人工介入）")
            unmark_in_progress(card_id, issue_number)
            return

        # 未超限：按测试 agent 声明的目标回退（默认开发）
        gh_edit(id=card_id, field_id=STATUS_FIELD_ID,
                single_select_option_id=OPTION_IDS[fail_target])
        log_step(original_title, f"{fail_target}(测试失败回退,第{fail_count}次)")
        print(f"{ts()} [agent] 已回退: {original_title!r} -> {fail_target}（第 {fail_count}/{MAX_TEST_FAILURES} 次失败）")
    else:
        print(f"{ts()} [agent] 失败: {original_title!r}，状态不变")
        # 超时留痕：现场输出写回 Issue 评论 / Draft 描述，重试的 agent 会自动读到并续跑
        if timed_out and output:
            trace = (f"## ⏱️ 上次 agent 超时（超过 {AGENT_TIMEOUT}s 未完成）\n\n"
                     f"以下为现场输出末尾，重试时请在此基础上继续：\n\n{output}")
            if issue_number:
                add_comment(issue_number, trace)
                print(f"{ts()} [agent] 已记录超时现场到 Issue 评论")
            else:
                try:
                    append_draft_body(card_id, content_id, trace)
                    print(f"{ts()} [agent] 已记录超时现场到卡片描述")
                except Exception as e:
                    print(f"{ts()} [agent] 记录超时现场失败: {e}")

    unmark_in_progress(card_id, issue_number)


# ---------- 模式二：主脚本（cron 调用） ----------

def launch_agent(card_id, original_title, current, target, issue_number, content_id, reason=""):
    """起后台 agent: --agent card_id title current target [issue_number] [content_id]。reason 用于日志。"""
    cmd = ["timeout", str(AGENT_TIMEOUT + 120),
           sys.executable, __file__, "--agent",
           card_id, original_title, current, target]
    if issue_number:
        cmd.append(str(issue_number))
    if content_id:
        cmd.append(content_id)
    subprocess.Popen(
        cmd,
        stdout=open(LOG_OUTPUT, "a"),
        stderr=subprocess.STDOUT,
        start_new_session=True,
        stdin=subprocess.DEVNULL,
    )
    suffix = f"（{reason}）" if reason else ""
    print(f"{ts()} 已启动后台 agent{suffix}: {original_title!r} -> {target}", flush=True)


def run_scan_mode():
    """扫描看板，为每张待处理卡起后台 agent，秒级返回。"""
    items = get_items()
    # 子任务执行顺序保证：按 issue number 升序处理。子卡由需求 agent 的 JSON 顺序创建
    # （先全部后端卡、后全部前端卡），后端卡号小前端卡号大；升序即先后端后前端，
    # 各列（计划/开发/测试）每轮并发 1，队列顺序 = 执行顺序。DraftIssue 无编号排最后。
    items.sort(key=lambda c: (c.get("content", {}).get("number") is None,
                              c.get("content", {}).get("number") or 0))
    busy_columns = set()
    launched = 0
    resolve_option_ids()

    # issue 编号 → 看板条目 映射（父卡聚合用）
    items_by_number = {}
    for card in items:
        content = card.get("content", {})
        n = content.get("number") if content.get("type") == "Issue" else None
        if n:
            items_by_number[n] = card

    for card in items:
        title = card["title"]
        current = card.get("status")
        card_id = card["id"]
        original_title = title

        # 提取 content id 和 issue number（标签路由与启动共用）
        content = card.get("content", {})
        content_id = content.get("id")  # DI_ 前缀，用于 DraftIssue body
        issue_number = content.get("number") if content.get("type") == "Issue" else None
        labels = get_issue_labels(issue_number) if issue_number else set()

        # 父卡聚合（优先级最高）：已分解或有子任务的卡永不启动 agent，
        # 全部子卡 Done 后自动移 Done；父卡忽略路由标签
        if issue_number and current not in (None, "Done") and CHILD_LABEL not in labels:
            route = next((l for l in ROUTE_LABELS if l in labels), None)
            if route:
                remove_label(issue_number, label=route)
                print(f"{ts()} 父卡忽略路由标签: {title!r}", flush=True)
            if aggregate_parent(card, issue_number, labels, items_by_number):
                continue

        # 标签路由：需要重新计划 -> 需求 agent（目标开发）；需要改动 -> 开发 agent（目标测试）
        route_label = None
        route_target = None
        for label, target in ROUTE_LABELS.items():
            if label in labels:
                route_label, route_target = label, target
                break
        if route_target:
            # 虚拟列 = 该目标对应的处理列（需求->开发 由需求 agent 处理；开发->测试 由开发 agent 处理）
            virtual = "需求" if route_target == "开发" else "开发"

            # 处理中标记检查：Issue 卡看「⏳ 处理中」标签，DraftIssue 卡看锁文件
            in_progress, fatal = in_progress_state(card_id, issue_number, labels)
            if fatal:
                return
            if in_progress:
                print(f"{ts()} 跳过(处理中): {title!r}", flush=True)
                busy_columns.add(virtual)
                continue
            if virtual in busy_columns:
                print(f"{ts()} 跳过(列忙): {title!r} 列 {virtual!r} 已有工作在进行", flush=True)
                continue
            busy_columns.add(virtual)

            # 摘路由标签（避免下轮重复触发），并清除可能的 halt 停止标记
            remove_label(issue_number, label=route_label)
            clear_fail_state(card_id, issue_number)

            # 先把卡片移到对应处理列（需要重新计划 -> 需求；需要改动 -> 开发），
            # 看板状态与实际处理阶段保持一致；随后同轮直接启动 agent
            try:
                gh_edit(id=card_id, field_id=STATUS_FIELD_ID,
                        single_select_option_id=OPTION_IDS[virtual])
                log_step(original_title, f"{virtual}(标签路由:{route_label})")
            except subprocess.CalledProcessError as e:
                print(f"{ts()} [label] 移列失败: {e}", flush=True)
            launch_agent(card_id, original_title, virtual, route_target, issue_number, content_id,
                         reason=f"标签路由:{route_label}")
            launched += 1
            continue

        if current in (None, "Todo", "评审"):
            note = "预留" if current == "评审" else "手动控制"
            print(f"{ts()} 跳过({note}): {title!r} 状态 {current!r}"
                  + ("" if current == "评审" else "，请手动移到「需求」"), flush=True)
            continue
        if current == "Done":
            print(f"{ts()} 跳过(已完成): {title!r}", flush=True)
            continue

        # 处理中标记检查：Issue 卡看「⏳ 处理中」标签，DraftIssue 卡看锁文件
        in_progress, fatal = in_progress_state(card_id, issue_number, labels)
        if fatal:
            return
        if in_progress:
            print(f"{ts()} 跳过(处理中): {title!r}", flush=True)
            busy_columns.add(current)
            continue

        # 已因测试失败超限停止流转的卡：不再自动处理，直到人工 --reset 或加路由标签
        if is_halted(card_id, issue_number, labels):
            print(f"{ts()} 跳过(已停止流转): {title!r} 测试失败达 {MAX_TEST_FAILURES} 次，"
                  f"请人工介入后运行 --reset {card_id} 或加路由标签", flush=True)
            continue

        if current in busy_columns:
            print(f"{ts()} 跳过(列忙): {title!r} 列 {current!r} 已有工作在进行", flush=True)
            continue
        busy_columns.add(current)

        target = TRANSITIONS[current]

        # 起后台 agent
        launch_agent(card_id, original_title, current, target, issue_number, content_id)
        launched += 1

    print(f"{ts()} 完成，共启动 {launched} 个后台 agent", flush=True)


# ---------- 入口 ----------

def run_loop_mode(interval=600):
    """自循环模式：每 interval 秒从扫描看板重新开始一轮。
    每轮独立获取 cron flock（与 cron 实例互斥，重叠时跳过本轮）；
    启动的后台 agent 均独立进程组，循环退出不影响它们。"""
    print(f"{ts()} [loop] 自循环模式启动，每 {interval}s 扫描一次（Ctrl+C 退出）", flush=True)
    while True:
        next_run = time.time() + interval
        cron_lock_fd = None
        try:
            cron_lock_fd = open(CRON_LOCK, "w")
            fcntl.flock(cron_lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            cron_lock_fd.write(str(os.getpid()))
            cron_lock_fd.flush()
        except BlockingIOError:
            print(f"{ts()} [loop] 另一个实例正在运行，本轮跳过", flush=True)
        else:
            try:
                run_scan_mode()
            except Exception as e:
                # 单轮失败（如 API 限流、gh 抖动）不退出循环，记日志下轮重试
                print(f"{ts()} [loop] 本轮扫描失败: {e!r}，下轮重试", flush=True)
            finally:
                if cron_lock_fd:
                    fcntl.flock(cron_lock_fd, fcntl.LOCK_UN)
                    cron_lock_fd.close()
        # 分段睡眠，可及时响应 Ctrl+C
        while True:
            remaining = next_run - time.time()
            if remaining <= 0:
                break
            try:
                time.sleep(min(remaining, 60))
            except KeyboardInterrupt:
                print(f"\n{ts()} [loop] 收到 Ctrl+C，退出循环", flush=True)
                return


def main():
    # --reset 模式：清除卡片失败状态，恢复自动流转
    if "--reset" in sys.argv:
        idx = sys.argv.index("--reset") + 1
        if idx >= len(sys.argv):
            print(f"{ts()} 用法: {sys.argv[0]} --reset <card_id>")
            sys.exit(1)
        card_id = sys.argv[idx]
        issue_number = find_issue_number(card_id)
        clear_fail_state(card_id, issue_number)
        print(f"{ts()} 已清除卡片 {card_id} 的失败状态，可重新自动流转")
        return

    # --loop 模式：自循环扫描（--interval 秒，默认 300）
    if "--loop" in sys.argv:
        interval = 600
        if "--interval" in sys.argv:
            i = sys.argv.index("--interval")
            interval = int(sys.argv[i + 1])
        run_loop_mode(interval)
        return

    # --agent 模式：后台 agent 直接执行
    # 参数顺序: card_id title current target [issue_number] [content_id]
    if "--agent" in sys.argv:
        # 外层 timeout 杀 python 时，先清理 pi 进程组与空闲 daemon 再退出
        signal.signal(signal.SIGTERM, _sigterm_handler)
        idx = sys.argv.index("--agent") + 1
        card_id, title, current, target = (sys.argv[idx], sys.argv[idx + 1],
                                           sys.argv[idx + 2], sys.argv[idx + 3])
        issue_num = None
        content_id = None
        # 检查第5个参数（idx+4）是否为数字（issue_number）
        if len(sys.argv) > idx + 4 and sys.argv[idx + 4].isdigit():
            issue_num = int(sys.argv[idx + 4])
            # 如果有 issue_number，content_id 在 idx+5
            if len(sys.argv) > idx + 5:
                content_id = sys.argv[idx + 5]
        elif len(sys.argv) > idx + 4:
            # 没有 issue_number，content_id 在 idx+4
            content_id = sys.argv[idx + 4]
        run_agent_mode(card_id, title, current, target, issue_num, content_id)
        return

    # cron 重叠防护
    cron_lock_fd = None
    try:
        cron_lock_fd = open(CRON_LOCK, "w")
        fcntl.flock(cron_lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        cron_lock_fd.write(str(os.getpid()))
        cron_lock_fd.flush()
    except BlockingIOError:
        print(f"{ts()} 另一个实例正在运行，跳过")
        sys.exit(0)

    try:
        run_scan_mode()
    finally:
        if cron_lock_fd:
            fcntl.flock(cron_lock_fd, fcntl.LOCK_UN)
            cron_lock_fd.close()


if __name__ == "__main__":
    main()
