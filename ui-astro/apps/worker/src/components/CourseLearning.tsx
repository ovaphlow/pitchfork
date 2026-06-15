import { useState, useEffect } from "react";
import { listCourses, listChapters, updateLearningProgress, completeLearning, type Course, type Chapter } from "@pitchfork/shared";
import { Button, Badge, LoadingSpinner } from "@pitchfork/ui";

function usePathId() {
  if (typeof window === "undefined") return "";
  const m = window.location.pathname.match(/\/training\/([^/]+)/);
  return m ? m[1] : "";
}

export default function CourseLearning() {
  const courseId = usePathId();
  const [course, setCourse] = useState<Course | null>(null);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [activeChapter, setActiveChapter] = useState<string>("");
  const [completedChapters, setCompletedChapters] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!courseId) return;
    setLoading(true);
    Promise.all([
      listCourses({ limit: 1 }).then((courses) => courses.find((c) => c.id === courseId)),
      listChapters(courseId),
    ])
      .then(([course, chapters]) => {
        setCourse(course ?? null);
        setChapters(chapters);
        if (chapters.length > 0) setActiveChapter(chapters[0].id);
      })
      .finally(() => setLoading(false));
  }, [courseId]);

  const handleMarkComplete = async (chapterId: string) => {
    try {
      await updateLearningProgress("", "", chapterId, { completed: true });
      setCompletedChapters((prev) => new Set(prev).add(chapterId));
    } catch {
      // Silent fail for demo
    }
  };

  const handleCompleteCourse = async () => {
    try {
      await completeLearning("", "");
      // Show success
    } catch {
      // Silent fail
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  if (!course) {
    return (
      <div className="flex flex-col items-center py-20 text-center">
        <span className="text-4xl mb-3">📖</span>
        <p className="text-sm text-fg-dimmed">课程未找到</p>
      </div>
    );
  }

  const currentChapter = chapters.find((c) => c.id === activeChapter);
  const allComplete = chapters.length > 0 && chapters.every((c) => completedChapters.has(c.id));
  const progress = chapters.length > 0 ? Math.round((completedChapters.size / chapters.length) * 100) : 0;

  return (
    <div>
      {/* Course Info */}
      <div className="mb-4">
        <h1 className="text-lg font-semibold text-fg-emphasis mb-1">{course.title}</h1>
        {course.description && (
          <p className="text-sm text-fg-muted mb-2">{course.description}</p>
        )}
        <div className="flex items-center gap-2 text-xs text-fg-dimmed">
          {course.difficulty && <Badge>{course.difficulty}</Badge>}
          {course.duration && <span>时长: {course.duration}分钟</span>}
          <span className="ml-auto">进度: {progress}%</span>
        </div>
        <div className="mt-2 h-1.5 rounded-full bg-surface-alt overflow-hidden">
          <div
            className="h-full rounded-full bg-accent transition-all duration-500"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      {/* Chapter Nav */}
      <div className="flex gap-2 overflow-x-auto pb-2 mb-4 scrollbar-none">
        {chapters.map((ch, idx) => (
          <button
            key={ch.id}
            onClick={() => setActiveChapter(ch.id)}
            className={`shrink-0 h-8 px-3 rounded-full text-xs font-medium transition-all cursor-pointer border-none ${
              activeChapter === ch.id
                ? "bg-accent text-white"
                : completedChapters.has(ch.id)
                ? "bg-success-bg text-success"
                : "bg-surface-alt text-fg-muted hover:text-fg"
            }`}
          >
            {completedChapters.has(ch.id) ? "✓ " : ""}第{idx + 1}章
          </button>
        ))}
      </div>

      {/* Chapter Content */}
      {currentChapter && (
        <div className="rounded-lg border border-border bg-surface p-4 mb-4">
          <h2 className="text-base font-semibold text-fg-emphasis mb-3">{currentChapter.title}</h2>
          <div
            className="text-sm text-fg leading-relaxed whitespace-pre-wrap"
            dangerouslySetInnerHTML={{ __html: currentChapter.content || "暂无内容" }}
          />
          {!completedChapters.has(currentChapter.id) && (
            <Button
              className="mt-4 w-full"
              onClick={() => handleMarkComplete(currentChapter.id)}
            >
              标记为已完成
            </Button>
          )}
        </div>
      )}

      {allComplete && (
        <Button variant="secondary" className="w-full mb-4" onClick={handleCompleteCourse}>
          🎉 完成课程
        </Button>
      )}
    </div>
  );
}
