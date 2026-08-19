// Service unit tests for the drill assessments (演练考核评估): the input
// validation and defaults of normalizeAssessment, the idempotent
// (run, point) upsert through the service over the in-memory store
// (create / update in place / writable-run and point-ownership checks),
// the single-record query with its run/record 404 distinction, the
// sorted + paginated list, and the delete with its writable-run check.
// The tests never touch a database; the service clock and id generator
// are injected so ordering and timestamps are deterministic.
package drills

import (
	"context"
	"errors"
	"testing"
	"time"
)

// mustPutAssessment upserts an assessment with the given input, failing
// the test on error.
func mustPutAssessment(t *testing.T, service *Service, runID, pointID string, input AssessmentInput) Assessment {
	t.Helper()
	assessment, err := service.UpsertAssessment(context.Background(), runID, pointID, input)
	if err != nil {
		t.Fatalf("UpsertAssessment: %v", err)
	}
	return assessment
}

// ─── normalizeAssessment ─────────────────────────────────────────────

// score 0 与 100 均为合法边界值；comment / created_by 透传；run_id /
// point_id / id 与时间戳来自调用方。
func TestNormalizeAssessmentBoundariesAndPassthrough(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)

	assessment, err := normalizeAssessment("run-001", "point-001", AssessmentInput{
		Score: 0, Comment: "达标", CreatedBy: "u-admin",
	}, now, "assessment-001")
	if err != nil {
		t.Fatalf("normalizeAssessment score 0: %v", err)
	}
	if assessment.Score != 0 || assessment.Comment != "达标" || assessment.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", assessment)
	}
	if assessment.RunID != "run-001" || assessment.PointID != "point-001" || assessment.ID != "assessment-001" ||
		!assessment.CreatedAt.Equal(now) || !assessment.UpdatedAt.Equal(now) {
		t.Fatalf("key fields / timestamps = %+v", assessment)
	}

	assessment, err = normalizeAssessment("run-001", "point-001", AssessmentInput{Score: 100}, now, "assessment-002")
	if err != nil {
		t.Fatalf("normalizeAssessment score 100: %v", err)
	}
	if assessment.Score != 100 || assessment.Comment != "" || assessment.CreatedBy != "" {
		t.Fatalf("score 100 / defaults = %+v", assessment)
	}
}

// score 越界（<0、>100）→ ValidationError。
func TestNormalizeAssessmentRejectsOutOfRangeScore(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	for _, score := range []int{-1, 101} {
		_, err := normalizeAssessment("run-001", "point-001", AssessmentInput{Score: score}, now, "assessment-001")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("score %d: err = %v, want a ValidationError", score, err)
		}
	}
}

// ─── UpsertAssessment ────────────────────────────────────────────────

// 首次 PUT 创建（服务端 id、score/comment/created_by 透传、created_at 与
// updated_at 相等）；再次 PUT 原地更新：id/created_at 不变、updated_at
// 刷新、全量替换（省略字段按默认值重置）；随后 GetAssessment 与
// ListAssessments 反映更新。
func TestUpsertAssessmentCreatesAndUpdatesInPlace(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)

	created := mustPutAssessment(t, service, run.ID, point.ID, AssessmentInput{
		Score: 85, Comment: "第一版", CreatedBy: "u-admin",
	})
	if created.ID == "" || created.RunID != run.ID || created.PointID != point.ID {
		t.Fatalf("created assessment = %+v", created)
	}
	if created.Score != 85 || created.Comment != "第一版" || created.CreatedBy != "u-admin" {
		t.Fatalf("created values = %+v", created)
	}
	if !created.CreatedAt.Equal(created.UpdatedAt) {
		t.Fatalf("created_at = %v, updated_at = %v; want equal", created.CreatedAt, created.UpdatedAt)
	}

	// 第二次 PUT：id/created_at 保留，updated_at 刷新，字段全量替换
	// （省略的 comment/created_by 按默认值重置）。
	updated := mustPutAssessment(t, service, run.ID, point.ID, AssessmentInput{Score: 90})
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Fatalf("created_at %v changed to %v on update", created.CreatedAt, updated.CreatedAt)
	}
	if updated.UpdatedAt.Equal(created.UpdatedAt) {
		t.Fatalf("updated_at %v must be refreshed on update", updated.UpdatedAt)
	}
	if updated.Score != 90 || updated.Comment != "" || updated.CreatedBy != "" {
		t.Fatalf("replacement semantics = %+v", updated)
	}

	// GetAssessment 与 ListAssessments 反映更新。
	fetched, err := service.GetAssessment(context.Background(), run.ID, point.ID)
	if err != nil {
		t.Fatalf("GetAssessment: %v", err)
	}
	if fetched.Score != 90 || fetched.Comment != "" {
		t.Fatalf("GetAssessment after PUT = %+v, want the updated values", fetched)
	}
	all, total, err := service.ListAssessments(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListAssessments: %v", err)
	}
	if total != 1 || len(all) != 1 || all[0].Score != 90 {
		t.Fatalf("ListAssessments after PUT = %+v / total %d, want the updated record", all, total)
	}
}

// 仅 进行中/已完成 可写：未开始/已终止 → ValidationError；run 不存在 →
// ErrRunNotFound；point 不存在或不属于 run 场景 → ErrPointNotFound。
func TestUpsertAssessmentWritableRunAndPointOwnership(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)
	notStarted := mustCreateRun(t, service, scenario.ID, runInput)
	inProgress := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, inProgress.ID)
	completed := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, completed.ID)
	mustCompleteRun(t, service, completed.ID)
	terminated := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, terminated.ID)
	if _, err := service.TerminateRun(context.Background(), terminated.ID); err != nil {
		t.Fatalf("setup terminate: %v", err)
	}

	// 未开始 / 已终止 → ValidationError。
	for _, run := range []Run{notStarted, terminated} {
		_, err := service.UpsertAssessment(context.Background(), run.ID, point.ID, AssessmentInput{Score: 80})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("upsert on %s run: err = %v, want a ValidationError", run.Status, err)
		}
	}
	// 进行中 / 已完成 → 成功。
	mustPutAssessment(t, service, inProgress.ID, point.ID, AssessmentInput{Score: 80})
	mustPutAssessment(t, service, completed.ID, point.ID, AssessmentInput{Score: 90})

	// run 不存在 → ErrRunNotFound。
	if _, err := service.UpsertAssessment(context.Background(), "run-missing", point.ID, AssessmentInput{Score: 80}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// point 不存在 → ErrPointNotFound。
	if _, err := service.UpsertAssessment(context.Background(), inProgress.ID, "point-missing", AssessmentInput{Score: 80}); !errors.Is(err, ErrPointNotFound) {
		t.Fatalf("missing point: err = %v, want ErrPointNotFound", err)
	}
	// point 属于另一场景 → ErrPointNotFound。
	otherScenario := mustCreateScenario(t, service, ScenarioInput{Name: "停电应急演练", Category: CategoryPowerOutage, Background: "市电中断"})
	foreignPoint := mustCreatePoint(t, service, otherScenario.ID, pointInput)
	if _, err := service.UpsertAssessment(context.Background(), inProgress.ID, foreignPoint.ID, AssessmentInput{Score: 80}); !errors.Is(err, ErrPointNotFound) {
		t.Fatalf("foreign point: err = %v, want ErrPointNotFound", err)
	}
}

// ─── GetAssessment ───────────────────────────────────────────────────

// 记录存在返回完整对象；run 不存在 → ErrRunNotFound；(run, point) 无记录
// → ErrAssessmentNotFound；DELETE 后再次 GET → ErrAssessmentNotFound。
func TestGetAssessment(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)
	created := mustPutAssessment(t, service, run.ID, point.ID, AssessmentInput{Score: 75, Comment: "总体达标"})

	fetched, err := service.GetAssessment(context.Background(), run.ID, point.ID)
	if err != nil {
		t.Fatalf("GetAssessment: %v", err)
	}
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.PointID != point.ID ||
		fetched.Score != 75 || fetched.Comment != "总体达标" {
		t.Fatalf("GetAssessment = %+v, want the created record", fetched)
	}

	// run 不存在。
	if _, err := service.GetAssessment(context.Background(), "run-missing", point.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// (run, point) 无记录（point 从未被评估）。
	otherPoint := mustCreatePoint(t, service, scenario.ID, PointInput{Title: "物资保障", Description: "考察物资保障是否到位"})
	if _, err := service.GetAssessment(context.Background(), run.ID, otherPoint.ID); !errors.Is(err, ErrAssessmentNotFound) {
		t.Fatalf("missing record: err = %v, want ErrAssessmentNotFound", err)
	}

	// DELETE 后 GET 单条 → ErrAssessmentNotFound。
	if err := service.DeleteAssessment(context.Background(), run.ID, point.ID); err != nil {
		t.Fatalf("DeleteAssessment: %v", err)
	}
	if _, err := service.GetAssessment(context.Background(), run.ID, point.ID); !errors.Is(err, ErrAssessmentNotFound) {
		t.Fatalf("GetAssessment after DELETE: err = %v, want ErrAssessmentNotFound", err)
	}
}

// ─── ListAssessments ─────────────────────────────────────────────────

// 按 created_at ASC 排序（先 PUT 的在前）；limit/offset 分页生效、
// meta.total 为分页前总数；run 不存在 → ErrRunNotFound。
func TestListAssessmentsSortedAndPaginated(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	points := make([]AssessmentPoint, 3)
	for i := range points {
		points[i] = mustCreatePoint(t, service, scenario.ID, PointInput{Title: "要点"})
	}
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)
	// 逆序 PUT：期望列表按 created_at ASC 返回先 PUT 的 point 3。
	for i := len(points) - 1; i >= 0; i-- {
		mustPutAssessment(t, service, run.ID, points[i].ID, AssessmentInput{Score: 60 + i*10})
	}

	all, total, err := service.ListAssessments(context.Background(), run.ID, ListFilter{Limit: 2, Offset: 0})
	if err != nil {
		t.Fatalf("ListAssessments: %v", err)
	}
	if total != 3 || len(all) != 2 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 3", len(all), total)
	}
	if all[0].PointID != points[2].ID || all[1].PointID != points[1].ID {
		t.Fatalf("first page = %s %s, want point 3 then point 2 (created_at ASC)", all[0].PointID, all[1].PointID)
	}

	all, total, err = service.ListAssessments(context.Background(), run.ID, ListFilter{Limit: 2, Offset: 2})
	if err != nil {
		t.Fatalf("ListAssessments: %v", err)
	}
	if total != 3 || len(all) != 1 || all[0].PointID != points[0].ID {
		t.Fatalf("limit=2 offset=2: records = %d, total = %d; want 1 / 3", len(all), total)
	}

	// 空列表：records 为空、total 为 0（另一 run 无评估）。
	emptyRun := mustCreateRun(t, service, scenario.ID, runInput)
	all, total, err = service.ListAssessments(context.Background(), emptyRun.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListAssessments empty: %v", err)
	}
	if total != 0 || len(all) != 0 {
		t.Fatalf("empty list: records = %d, total = %d; want 0 / 0", len(all), total)
	}

	// run 不存在 → ErrRunNotFound。
	if _, _, err := service.ListAssessments(context.Background(), "run-missing", ListFilter{Limit: 50}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}

// ─── DeleteAssessment ────────────────────────────────────────────────

// 成功删除后列表不含该记录；仅 进行中/已完成 可写（未开始/已终止 →
// ValidationError）；(run, point) 无记录 → ErrAssessmentNotFound；run 不
// 存在 → ErrRunNotFound。
func TestDeleteAssessment(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	pointA := mustCreatePoint(t, service, scenario.ID, PointInput{Title: "要点A"})
	pointB := mustCreatePoint(t, service, scenario.ID, PointInput{Title: "要点B"})
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)
	mustPutAssessment(t, service, run.ID, pointA.ID, AssessmentInput{Score: 70})
	mustPutAssessment(t, service, run.ID, pointB.ID, AssessmentInput{Score: 80})

	if err := service.DeleteAssessment(context.Background(), run.ID, pointA.ID); err != nil {
		t.Fatalf("DeleteAssessment: %v", err)
	}
	all, total, err := service.ListAssessments(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListAssessments: %v", err)
	}
	if total != 1 || len(all) != 1 || all[0].PointID != pointB.ID {
		t.Fatalf("list after DELETE = %+v / total %d, want only the remaining record", all, total)
	}
	// 已删除的 (run, point) 再次 DELETE → ErrAssessmentNotFound。
	if err := service.DeleteAssessment(context.Background(), run.ID, pointA.ID); !errors.Is(err, ErrAssessmentNotFound) {
		t.Fatalf("delete again: err = %v, want ErrAssessmentNotFound", err)
	}

	// 未开始 / 已终止 → ValidationError。
	notStarted := mustCreateRun(t, service, scenario.ID, runInput)
	terminated := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, terminated.ID)
	if _, err := service.TerminateRun(context.Background(), terminated.ID); err != nil {
		t.Fatalf("setup terminate: %v", err)
	}
	for _, run := range []Run{notStarted, terminated} {
		err := service.DeleteAssessment(context.Background(), run.ID, pointA.ID)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("delete on %s run: err = %v, want a ValidationError", run.Status, err)
		}
	}

	// run 不存在 → ErrRunNotFound。
	if err := service.DeleteAssessment(context.Background(), "run-missing", pointA.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}
