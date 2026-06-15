import { useState, useEffect, useRef } from "react";
import { listExamPapers, startExam, submitExam, type ExamPaper, type ExamRecord } from "@pitchfork/shared";
import { Button, Badge, LoadingSpinner } from "@pitchfork/ui";

function usePathId() {
  if (typeof window === "undefined") return "";
  const m = window.location.pathname.match(/\/exam\/([^/]+)/);
  return m ? m[1] : "";
}

export default function ExamPage() {
  const paperId = usePathId();
  const [paper, setPaper] = useState<ExamPaper | null>(null);
  const [record, setRecord] = useState<ExamRecord | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [timeLeft, setTimeLeft] = useState(0);
  const [loading, setLoading] = useState(true);
  const [started, setStarted] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!paperId) return;
    listExamPapers({ limit: 1 })
      .then((papers) => {
        const p = papers.find((p) => p.id === paperId);
        setPaper(p ?? null);
      })
      .finally(() => setLoading(false));
  }, [paperId]);

  const handleStart = async () => {
    if (!paperId) return;
    try {
      const rec = await startExam(paperId);
      setRecord(rec);
      setStarted(true);
      if (paper?.duration) {
        setTimeLeft(paper.duration * 60);
        timerRef.current = setInterval(() => {
          setTimeLeft((prev) => {
            if (prev <= 1) {
              clearInterval(timerRef.current!);
              return 0;
            }
            return prev - 1;
          });
        }, 1000);
      }
    } catch {
      // Silent fail
    }
  };

  const handleSubmit = async () => {
    if (!record?.id) return;
    try {
      await submitExam(record.id, answers);
      setSubmitted(true);
      if (timerRef.current) clearInterval(timerRef.current);
    } catch {
      // Silent fail
    }
  };

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  };

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  if (!paper) {
    return (
      <div className="flex flex-col items-center py-20 text-center">
        <span className="text-4xl mb-3">📝</span>
        <p className="text-sm text-fg-dimmed">试卷未找到</p>
      </div>
    );
  }

  if (submitted) {
    return (
      <div className="flex flex-col items-center py-16 text-center">
        <span className="text-5xl mb-4">🎉</span>
        <h2 className="text-lg font-semibold text-fg-emphasis mb-2">答卷已提交</h2>
        <p className="text-sm text-fg-dimmed mb-4">请等待批阅结果</p>
        <Button onClick={() => (window.location.href = "/training")}>返回培训</Button>
      </div>
    );
  }

  if (!started) {
    return (
      <div className="flex flex-col items-center py-16 text-center">
        <span className="text-4xl mb-4">📝</span>
        <h2 className="text-lg font-semibold text-fg-emphasis mb-2">{paper.title}</h2>
        {paper.description && (
          <p className="text-sm text-fg-dimmed mb-2">{paper.description}</p>
        )}
        <div className="text-xs text-fg-muted mb-6 space-y-1">
          <p>总分: {paper.total_score} 分</p>
          <p>及格: {paper.pass_score} 分</p>
          <p>时长: {paper.duration} 分钟</p>
          <p>题量: {paper.question_count} 题</p>
        </div>
        <Button onClick={handleStart}>开始考试</Button>
      </div>
    );
  }

  return (
    <div>
      {/* Timer & Status */}
      <div className="flex items-center justify-between mb-4 p-3 rounded-lg border border-border bg-surface">
        <span className="text-sm font-medium text-fg">
          剩余时间: <span className={timeLeft < 300 ? "text-danger" : "text-accent"}>{formatTime(timeLeft)}</span>
        </span>
        <Button variant="danger" size="sm" onClick={handleSubmit}>
          交卷
        </Button>
      </div>

      <p className="text-sm text-fg-dimmed mb-4">
        请回答以下问题（演示界面，实际题目由API提供）
      </p>

      <Button className="w-full" onClick={handleSubmit}>
        提交答案
      </Button>
    </div>
  );
}
