package drills

import (
	"context"
	"errors"
	"testing"
)

// recordingScoreCleaner is the test fixture behind the
// EvaluationScoreCleaner interface: it records the run ids passed to
// DeleteScoresByRun (the real evaluation score store is wired at the
// composition root; the fixture stands in for it at the service level).
type recordingScoreCleaner struct{ deleted []string }

func (c *recordingScoreCleaner) DeleteScoresByRun(_ context.Context, runID string) error {
	c.deleted = append(c.deleted, runID)
	return nil
}

// 接线 evaluation-score cleaner 后 DeleteRun 级联调用 DeleteScoresByRun
// （run 的评估评分随 run 一起删除，对应 DB 的 ON DELETE CASCADE）；删除
// 不存在的 run 返回 ErrRunNotFound 且不触碰 cleaner。
func TestDeleteRunCascadesToEvaluationScores(t *testing.T) {
	store := NewInMemoryStore()
	service := NewService(store)
	scenario := mustCreateScenario(t, service, testScenarioInput)
	run := mustCreateRun(t, service, scenario.ID, RunInput{Title: "A"})

	cleaner := &recordingScoreCleaner{}
	service.SetEvaluationScoreCleaner(cleaner)
	if err := service.DeleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}
	if len(cleaner.deleted) != 1 || cleaner.deleted[0] != run.ID {
		t.Fatalf("DeleteScoresByRun calls = %v, want [%s]", cleaner.deleted, run.ID)
	}

	// 删除不存在的 run：ErrRunNotFound，不触碰 cleaner。
	if err := service.DeleteRun(context.Background(), "run-missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("err = %v, want ErrRunNotFound", err)
	}
	if len(cleaner.deleted) != 1 {
		t.Fatalf("cleaner calls after missing run = %v, want unchanged", cleaner.deleted)
	}
}
