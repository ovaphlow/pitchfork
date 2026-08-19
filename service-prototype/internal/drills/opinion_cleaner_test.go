package drills

import (
	"context"
	"errors"
	"testing"
)

// recordingOpinionCleaner is the test fixture behind the
// RunOpinionCleaner interface: it records the run ids passed to
// DeleteByRun (the real opinion store is wired at the composition root;
// the fixture stands in for it at the service level).
type recordingOpinionCleaner struct{ deleted []string }

func (c *recordingOpinionCleaner) DeleteByRun(_ context.Context, runID string) error {
	c.deleted = append(c.deleted, runID)
	return nil
}

// 接线 run-opinion cleaner 后 DeleteRun 级联调用 DeleteByRun（run 的舆情
// 对象——本卡为舆情事件配置——随 run 一起删除，对应 DB 的 ON DELETE
// CASCADE）；删除不存在的 run 返回 ErrRunNotFound 且不触碰 cleaner；
// 未接线时 DeleteRun 行为不变（不调用 cleaner，也不报错）。
func TestDeleteRunCascadesToOpinionObjects(t *testing.T) {
	store := NewInMemoryStore()
	service := NewService(store)
	scenario := mustCreateScenario(t, service, testScenarioInput)
	run := mustCreateRun(t, service, scenario.ID, RunInput{Title: "A"})

	cleaner := &recordingOpinionCleaner{}
	service.SetOpinionCleaner(cleaner)
	if err := service.DeleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}
	if len(cleaner.deleted) != 1 || cleaner.deleted[0] != run.ID {
		t.Fatalf("DeleteByRun calls = %v, want [%s]", cleaner.deleted, run.ID)
	}

	// 删除不存在的 run：ErrRunNotFound，不触碰 cleaner。
	if err := service.DeleteRun(context.Background(), "run-missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("err = %v, want ErrRunNotFound", err)
	}
	if len(cleaner.deleted) != 1 {
		t.Fatalf("cleaner calls after missing run = %v, want unchanged", cleaner.deleted)
	}

	// 未接线 cleaner：DeleteRun 照常工作，不调用 cleaner。
	unwired := NewService(NewInMemoryStore())
	scenario2 := mustCreateScenario(t, unwired, testScenarioInput)
	run2 := mustCreateRun(t, unwired, scenario2.ID, RunInput{Title: "B"})
	if err := unwired.DeleteRun(context.Background(), run2.ID); err != nil {
		t.Fatalf("DeleteRun without cleaner: %v", err)
	}
}
