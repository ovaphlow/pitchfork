#!/usr/bin/env python3
"""看板状态流转（后台模式）：cron 秒级返回，pi agent 在后台运行。

主脚本：扫看板 → 对每张待处理卡起后台 agent → 立即退出。
后台 agent：pi 完成文件改动 → 自己调 gh 更新看板状态。

Todo 列不处理（手动控制），从「需求」开始：
  需求 -> 「需求 agent」分析需求、形成文档   -> 开发
  开发 -> 「开发 agent」编码实现             -> 测试
  测试 -> 「测试 agent」验证                 -> Done

每列并发限制：同一次运行中，每个 status 列最多起一个 agent。
防重复触发：后台 agent 通过锁文件标记处理中状态。
"""
import subprocess
import json
import os
import sys
import fcntl
from datetime import datetime

PROJECT_NUMBER = "8"
OWNER = "@me"
PROJECT_ID = "PVT_kwHOAAOE884AYQef"
REPO_DIR = os.path.dirname(os.path.abspath(__file__))

CRON_LOCK = "/tmp/kanban_automator_cron.lock"
LOG_FILE = "kanban_history.txt"
LOG_OUTPUT = "/tmp/kanban_automator.log"
LOCK_FILE = "/tmp/kanban_automator.lock"

IN_PROGRESS_LABEL = "⏳ 处理中"
STALE_GRACE = 60

# pi agent 调用参数
AGENT_MODEL = None
AGENT_TIMEOUT = 900
AGENT_CMD = ["pi", "-p", "--mode", "text"]
if AGENT_MODEL:
    AGENT_CMD += ["--model", AGENT_MODEL]
AGENT_CMD += ["--approve"]

# Status 字段（single-select）及其选项 ID
STATUS_FIELD_ID = "PVTSSF_lAHOAAOE884AYQefzgPghq8"
OPTION_IDS = {
    "Todo": "f75ad846",
    "需求": "6ff7dcda",
    "开发": "47fc9ee4",
    "测试": "ceaca6cd",
    "Done": "98236657",
}

TRANSITIONS = {
    "需求": "开发",
    "开发": "测试",
    "测试": "Done",
}

# 测试失败回退到开发
TEST_FAIL_TARGET = "开发"

STAGE_PROMPTS = {
    "需求": "你是需求分析 agent。请分析看板卡片的需求，形成清晰的需求规格说明。" \
              "直接输出分析结果（包含背景、目标、验收标准等），不要写入文件。",
    "开发": "你是开发 agent。卡片描述中包含完整的需求规格说明（背景、目标、验收标准）。" \
              "请仔细阅读描述，根据验收标准实现编码，不要询问用户。",
    "测试": "你是测试 agent。卡片描述中包含验收标准。" \
              "请逐一验证每条验收标准，运行相关测试，报告结果。" \
              "如果任何验收标准不满足，请在输出开头包含 ❌ 标记，并说明失败原因。",
}


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


# ---------- 处理中标记 / 锁 ----------

def load_lock():
    try:
        with open(LOCK_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_lock(lock):
    with open(LOCK_FILE, "w", encoding="utf-8") as f:
        json.dump(lock, f, ensure_ascii=False, indent=2)


def add_label(issue_number):
    """给 Issue 添加处理中标签"""
    try:
        subprocess.run(
            ["gh", "issue", "edit", str(issue_number), "--add-label", IN_PROGRESS_LABEL],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"[label] 添加标签失败: {e}")


def remove_label(issue_number):
    """移除 Issue 的处理中标签"""
    try:
        subprocess.run(
            ["gh", "issue", "edit", str(issue_number), "--remove-label", IN_PROGRESS_LABEL],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError:
        pass


def mark_in_progress(card_id, original_title, issue_number=None):
    # 添加标签（在页面可见）
    if issue_number:
        add_label(issue_number)
    lock = load_lock()
    lock[card_id] = {
        "original_title": original_title,
        "issue_number": issue_number,
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "pid": os.getpid(),
    }
    save_lock(lock)


def unmark_in_progress(card_id, fallback_title=None):
    lock = load_lock()
    entry = lock.pop(card_id, None)
    save_lock(lock)
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


# ---------- agent / 状态 ----------

def run_agent(prompt, capture_output=False):
    print("--- 调用 pi agent ---")
    try:
        result = subprocess.run(
            AGENT_CMD + [prompt], cwd=REPO_DIR, timeout=AGENT_TIMEOUT,
            capture_output=capture_output, text=True,
        )
        if capture_output:
            return result.returncode == 0, result.stdout
        return result.returncode == 0, None
    except subprocess.TimeoutExpired:
        print(f"[超时] agent 超过 {AGENT_TIMEOUT}s 未完成")
        return False, None


def add_comment(issue_number, body):
    """给 Issue 添加评论"""
    try:
        subprocess.run(
            ["gh", "issue", "comment", str(issue_number), "--body", body],
            cwd=REPO_DIR, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"[comment] 添加评论失败: {e}")


def log_step(title, target_status):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(f"{ts}  {title}: {target_status}\n")


# ---------- 模式一：后台 agent（被主脚本 fork） ----------

def run_agent_mode(card_id, original_title, target, issue_number=None, content_id=None):
    """单张卡的 agent 执行：mark → pi → gh 更新 → unmark。"""
    # 从 target 反推 current（用于选 prompt）
    reverse = {v: k for k, v in TRANSITIONS.items()}
    current = reverse.get(target, "需求")
    
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
    
    prompt = STAGE_PROMPTS[current]
    prompt += f"\n\n看板标题：{original_title}"
    if card_body:
        prompt += f"\n\n看板描述：\n{card_body}"

    print(f"[agent] 开始: {original_title!r} ({current} -> {target})")
    mark_in_progress(card_id, original_title, issue_number)

    # 需求和测试阶段捕获输出
    capture = current in ("需求", "测试")
    ok, output = run_agent(prompt, capture_output=capture)

    # 检查测试是否失败（通过输出中的标志）
    test_failed = False
    failure_reason = None
    if ok and current == "测试" and output:
        output_lower = output.lower()
        # 测试 agent 输出中包含失败标志
        if any(fail_marker in output_lower for fail_marker in ["❌", "失败", "未通过", "fail", "not pass"]):
            test_failed = True
            failure_reason = output.strip()[-4000:] if len(output) > 4000 else output.strip()
            print(f"[agent] 测试未通过，将回退到开发")

    if ok and not test_failed:
        # 需求阶段：在原文后追加计划（DraftIssue 用 content_id）
        if current == "需求" and output:
            plan = output.strip()
            if plan:
                try:
                    # 读取当前 body
                    items = json.loads(subprocess.check_output([
                        "gh", "project", "item-list", PROJECT_NUMBER,
                        "--owner", OWNER, "--format", "json", "--limit", "100",
                    ]))
                    current_body = ""
                    for item in items["items"]:
                        if item["id"] == card_id:
                            current_body = item.get("content", {}).get("body", "")
                            break
                    # 追加计划到原文后面
                    new_body = f"{current_body.strip()}\n\n---\n\n{plan}"[-6000:]
                    edit_id = content_id if content_id else card_id
                    gh_edit(id=edit_id, body=new_body)
                    print(f"[agent] 已追加计划到描述: {len(plan)} chars")
                except subprocess.CalledProcessError as e:
                    print(f"[agent] 写入卡片描述失败: {e}")

        gh_edit(id=card_id, field_id=STATUS_FIELD_ID,
                single_select_option_id=OPTION_IDS[target])
        log_step(original_title, target)
        print(f"[agent] 完成: {original_title!r} -> {target}")
    elif test_failed:
        # 测试失败：回退到开发，记录失败原因
        fail_target = TRANSITIONS.get(current, "开发")
        fail_body = f"## 测试失败报告\n\n**失败时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n**失败原因**:\n\n{failure_reason}"
        
        # 更新描述（追加失败信息）
        try:
            edit_id = content_id if content_id else card_id
            # 读取当前 body
            items = json.loads(subprocess.check_output([
                "gh", "project", "item-list", PROJECT_NUMBER,
                "--owner", OWNER, "--format", "json", "--limit", "100",
            ]))
            current_body = ""
            for item in items["items"]:
                if item["id"] == card_id:
                    current_body = item.get("content", {}).get("body", "")
                    break
            # 追加失败信息
            new_body = f"{current_body}\n\n---\n\n{fail_body}"[-6000:]
            gh_edit(id=edit_id, body=new_body)
            print(f"[agent] 已记录失败原因到描述")
        except Exception as e:
            print(f"[agent] 更新描述失败: {e}")
        
        # 如果是 Issue，添加评论
        if issue_number:
            add_comment(issue_number, fail_body)
            print(f"[agent] 已添加失败评论到 Issue")
        
        # 回退状态到开发
        gh_edit(id=card_id, field_id=STATUS_FIELD_ID,
                single_select_option_id=OPTION_IDS[fail_target])
        log_step(original_title, f"{fail_target}(测试失败回退)")
        print(f"[agent] 已回退: {original_title!r} -> {fail_target}")
    else:
        print(f"[agent] 失败: {original_title!r}，状态不变")

    unmark_in_progress(card_id, original_title)


# ---------- 模式二：主脚本（cron 调用） ----------

def run_scan_mode():
    """扫描看板，为每张待处理卡起后台 agent，秒级返回。"""
    items = get_items()
    busy_columns = set()
    launched = 0

    for card in items:
        title = card["title"]
        current = card.get("status")
        card_id = card["id"]

        if current in (None, "Todo"):
            print(f"跳过(手动控制): {title!r} 状态 {current!r}，请手动移到「需求」")
            continue
        if current == "Done":
            print(f"跳过(已完成): {title!r}")
            continue

        # 处理中标记检查（通过锁文件）
        lock = load_lock()
        entry = lock.get(card_id)
        in_progress = False
        if entry:
            if not is_stale(entry):
                in_progress = True
            else:
                print(f"清除残留标记: {title!r}")
                unmark_in_progress(card_id)

        if in_progress:
            print(f"跳过(处理中): {title!r}")
            busy_columns.add(current)
            continue

        if current in busy_columns:
            print(f"跳过(列忙): {title!r} 列 {current!r} 已有工作在进行")
            continue
        busy_columns.add(current)

        target = TRANSITIONS[current]
        original_title = title

        # 提取 content id 和 issue number
        content = card.get("content", {})
        content_id = content.get("id")  # DI_ 前缀，用于 DraftIssue body
        issue_number = content.get("number") if content.get("type") == "Issue" else None

        # 起后台 agent: --agent card_id title target [issue_number] [content_id]
        cmd = ["timeout", str(AGENT_TIMEOUT + 120),
               sys.executable, __file__, "--agent",
               card_id, original_title, target]
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
        print(f"已启动后台 agent: {original_title!r} ({current} -> {target})")
        launched += 1

    print(f"完成，共启动 {launched} 个后台 agent")


# ---------- 入口 ----------

def main():
    # --agent 模式：后台 agent 直接执行
    # 参数顺序: card_id title target [issue_number] [content_id]
    if "--agent" in sys.argv:
        idx = sys.argv.index("--agent") + 1
        issue_num = None
        content_id = None
        # 检查第4个参数（idx+3）是否为数字（issue_number）
        if len(sys.argv) > idx + 3 and sys.argv[idx + 3].isdigit():
            issue_num = int(sys.argv[idx + 3])
            # 如果有 issue_number，content_id 在 idx+4
            if len(sys.argv) > idx + 4:
                content_id = sys.argv[idx + 4]
        elif len(sys.argv) > idx + 3:
            # 没有 issue_number，content_id 在 idx+3
            content_id = sys.argv[idx + 3]
        run_agent_mode(sys.argv[idx], sys.argv[idx + 1], sys.argv[idx + 2], issue_num, content_id)
        return

    # cron 重叠防护
    cron_lock_fd = None
    try:
        cron_lock_fd = open(CRON_LOCK, "w")
        fcntl.flock(cron_lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        cron_lock_fd.write(str(os.getpid()))
        cron_lock_fd.flush()
    except BlockingIOError:
        print("另一个实例正在运行，跳过")
        sys.exit(0)

    try:
        run_scan_mode()
    finally:
        if cron_lock_fd:
            fcntl.flock(cron_lock_fd, fcntl.LOCK_UN)
            cron_lock_fd.close()


if __name__ == "__main__":
    main()
