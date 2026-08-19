package evaluation

import (
	"context"
	"errors"
	"regexp"
	"testing"
	"time"
)

// ─── fixture 辅助 ────────────────────────────────────────────────────

// crockford26 matches a 26-character Crockford Base32 ULID (the
// alphabet omits I, L, O and U).
var crockford26 = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// fixtureReportSource implements ReportDataSource over plain fixture
// data, so the service tests never touch the drills/dispatch stores.
type fixtureReportSource struct {
	run         ReportRun
	runErr      error
	simEvents   []ReportSimEvent
	stepRecords []ReportStepRecord
	orders      []ReportOrder
	messages    []ReportMessage
	departments []ReportDepartmentReport
}

func (s *fixtureReportSource) GetRun(_ context.Context, _ string) (ReportRun, error) {
	return s.run, s.runErr
}

func (s *fixtureReportSource) ListSimEvents(_ context.Context, _ string) ([]ReportSimEvent, error) {
	return s.simEvents, nil
}

func (s *fixtureReportSource) ListStepRecords(_ context.Context, _ string) ([]ReportStepRecord, error) {
	return s.stepRecords, nil
}

func (s *fixtureReportSource) ListOrders(_ context.Context, _ string) ([]ReportOrder, error) {
	return s.orders, nil
}

func (s *fixtureReportSource) ListMessages(_ context.Context, _ string) ([]ReportMessage, error) {
	return s.messages, nil
}

func (s *fixtureReportSource) ListDepartmentReports(_ context.Context, _ string) ([]ReportDepartmentReport, error) {
	return s.departments, nil
}

// fixtureRunSourceError is the sentinel a fixture source returns for a
// missing run (the composition root would return the drills
// ErrRunNotFound; the service only passes it through).
var fixtureRunSourceError = errors.New("fixture run not found")

// newFixtureReportService builds a report service over fresh in-memory
// stores with a controllable clock and id sequence, so the service
// tests can assert the created_at/updated_at and the id semantics.
func newFixtureReportService(source ReportDataSource, indicatorStore Store, scoreStore ScoreStore, now func() time.Time) (*ReportService, *InMemoryReportStore, *idSequence) {
	reportStore := NewInMemoryReportStore()
	service := NewReportService(reportStore, indicatorStore, scoreStore, source)
	ids := &idSequence{}
	service.now = now
	service.newID = ids.next
	return service, reportStore, ids
}

type idSequence struct {
	index int
}

func (s *idSequence) next() string {
	s.index++
	return "report-id-" + string(rune('A'+s.index-1))
}

func fixedClock() func() time.Time {
	current := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	return func() time.Time { return current }
}

// completedRunFixture returns a completed run and its source.
func completedRunFixture() (ReportRun, *fixtureReportSource) {
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted}
	return run, &fixtureReportSource{run: run}
}

// ─── GenerateReport ──────────────────────────────────────────────────

// TestGenerateReportCreatesFirst asserts the first generation: created
// = true, a 26-character Crockford Base32 ULID id, the engine content
// (with the pinned empty 演练数据不足 shape for a bare run), created_by
// default ” and server-maintained timestamps.
func TestGenerateReportCreatesFirst(t *testing.T) {
	_, source := completedRunFixture()
	// 服务端真实 id 生成器：断言 26 位 Crockford Base32 ULID。
	service := NewReportService(NewInMemoryReportStore(), NewInMemoryStore(), NewInMemoryScoreStore(), source)
	service.now = fixedClock()

	report, created, err := service.GenerateReport(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GenerateReport: %v", err)
	}
	if !created {
		t.Error("created = false, want true on the first generation")
	}
	if !crockford26.MatchString(report.ID) {
		t.Errorf("id %q is not a 26-character Crockford Base32 ULID", report.ID)
	}
	if report.RunID != "run-1" {
		t.Errorf("run_id = %q, want run-1", report.RunID)
	}
	if report.CreatedBy != "" {
		t.Errorf("created_by = %q, want the default empty string", report.CreatedBy)
	}
	if !report.CreatedAt.Equal(service.now()) || !report.UpdatedAt.Equal(service.now()) {
		t.Errorf("created_at/updated_at = %v/%v, want the server clock %v", report.CreatedAt, report.UpdatedAt, service.now())
	}
	if report.OverallScore != 0 {
		t.Errorf("overall_score = %v, want 0 for the bare run", report.OverallScore)
	}
	if len(report.IndicatorScores) != 0 || len(report.DimensionScores) != 0 {
		t.Errorf("indicator/dimension scores = %v/%v, want empty", report.IndicatorScores, report.DimensionScores)
	}
	assertInsufficientDataSuggestion(t, report.Suggestions)

	stored, err := service.reports.GetReportByRun(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetReportByRun: %v", err)
	}
	if stored.ID != report.ID {
		t.Errorf("stored id = %q, want %q", stored.ID, report.ID)
	}
}

// TestGenerateReportOverwritesInPlace asserts the regeneration: created
// = false, the id and created_at are preserved, the updated_at is
// refreshed and the content reflects the new fixture data (the
// overwrite is effective).
func TestGenerateReportOverwritesInPlace(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted, StartedAt: timestamp(base, 0)}
	source := &fixtureReportSource{run: run}
	indicatorStore := NewInMemoryStore()
	indicator, _ := normalizeIndicator(IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "预警响应速度",
		SortOrder: intPtr(1),
	}, base, "ind-1")
	if err := indicatorStore.CreateIndicator(context.Background(), indicator); err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}
	scoreStore := NewInMemoryScoreStore()
	current := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	// 时钟随调用前进：首次生成 t1，重生成 t2 > t1，updated_at 必须刷新。
	advancing := func() time.Time {
		current = current.Add(time.Second)
		return current
	}
	service, store, _ := newFixtureReportService(source, indicatorStore, scoreStore, advancing)

	// 首次生成：无事件 → 预警响应速度无分 → 数据不足报告，总分 0。
	first, created, err := service.GenerateReport(context.Background(), "run-1")
	if err != nil || !created {
		t.Fatalf("first GenerateReport: created=%v err=%v", created, err)
	}

	// 新数据：sim 事件触发 + 一名专家评分，重生成后内容必须变化（覆盖生
	// 效）。
	source.simEvents = []ReportSimEvent{{TriggeredAt: timestamp(base, 10)}}
	score, _ := normalizeScore("run-1", "ind-1", ScoreInput{
		ScoreType: ScoreTypeExpert,
		Rater:     "评审员",
		Score:     intPtr(35),
	}, base, "score-1")
	if err := scoreStore.CreateScore(context.Background(), score); err != nil {
		t.Fatalf("CreateScore: %v", err)
	}

	second, created, err := service.GenerateReport(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("second GenerateReport: %v", err)
	}
	if created {
		t.Error("created = true, want false on regeneration")
	}
	if second.ID != first.ID {
		t.Errorf("id = %q, want %q (preserved across regeneration)", second.ID, first.ID)
	}
	if !second.CreatedAt.Equal(first.CreatedAt) {
		t.Errorf("created_at = %v, want %v (preserved across regeneration)", second.CreatedAt, first.CreatedAt)
	}
	if !second.UpdatedAt.After(first.UpdatedAt) {
		t.Errorf("updated_at = %v, want refreshed after %v", second.UpdatedAt, first.UpdatedAt)
	}
	// 覆盖生效：sim 事件给出自动分 98，专家 35 → 最终 (98+35)/2 = 66.5；
	// 数据不足提示消失。
	if second.OverallScore != 66.5 || second.OverallScore == first.OverallScore {
		t.Errorf("overall = %v (first %v), want 66.5 after the new data", second.OverallScore, first.OverallScore)
	}
	if len(second.IndicatorScores) != 1 || second.IndicatorScores["ind-1"].Score != 66.5 {
		t.Errorf("indicator_scores = %+v, want ind-1 with 66.5", second.IndicatorScores)
	}
	stored, err := store.GetReportByRun(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetReportByRun: %v", err)
	}
	if stored.ID != second.ID || stored.OverallScore != second.OverallScore {
		t.Errorf("stored report = %+v, want the regenerated content", stored)
	}
	// run_id 唯一单条：store 中仅一条报告。
	if total := len(store.reports); total != 1 {
		t.Errorf("stored reports = %d, want exactly 1", total)
	}
}

// TestGenerateReportRunMissing asserts that a missing run passes its
// not-found error through unchanged (404 at the routing layer) and
// never stores a report.
func TestGenerateReportRunMissing(t *testing.T) {
	source := &fixtureReportSource{runErr: fixtureRunSourceError}
	service, store, _ := newFixtureReportService(source, NewInMemoryStore(), NewInMemoryScoreStore(), fixedClock())

	report, created, err := service.GenerateReport(context.Background(), "run-missing")
	if !errors.Is(err, fixtureRunSourceError) {
		t.Fatalf("err = %v, want the fixture run-not-found error", err)
	}
	if created || report.ID != "" {
		t.Errorf("created = %v, report = %+v, want no report", created, report)
	}
	if _, err := store.GetReportByRun(context.Background(), "run-missing"); !errors.Is(err, ErrReportNotFound) {
		t.Errorf("GetReportByRun = %v, want ErrReportNotFound", err)
	}
}

// TestGenerateReportRunNotCompleted asserts that a run that is not
// 已完成 (未开始/进行中/已终止) is rejected with a ValidationError whose
// message names the current status (400 at the routing layer).
func TestGenerateReportRunNotCompleted(t *testing.T) {
	for _, status := range []string{"未开始", "进行中", "已终止"} {
		t.Run(status, func(t *testing.T) {
			source := &fixtureReportSource{run: ReportRun{ID: "run-1", Status: status}}
			service, _, _ := newFixtureReportService(source, NewInMemoryStore(), NewInMemoryScoreStore(), fixedClock())

			_, created, err := service.GenerateReport(context.Background(), "run-1")
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				t.Fatalf("err = %v, want a ValidationError", err)
			}
			want := "run status " + status + " does not allow report generation"
			if validationError.Message != want {
				t.Errorf("message = %q, want %q", validationError.Message, want)
			}
			if created {
				t.Error("created = true, want false")
			}
		})
	}
}

// TestGenerateReportWithScores asserts the end-to-end content of a
// generation: the engine output (scores, dimensions, overall,
// suggestions) becomes the report snapshot.
func TestGenerateReportWithScores(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted, StartedAt: timestamp(base, 0)}
	// 无 sim 事件 → 预警响应速度无自动分，仅专家来源计入。
	source := &fixtureReportSource{run: run}
	indicatorStore := NewInMemoryStore()
	indicator, _ := normalizeIndicator(IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "预警响应速度",
		SortOrder: intPtr(1),
	}, base, "ind-1")
	if err := indicatorStore.CreateIndicator(context.Background(), indicator); err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}
	scoreStore := NewInMemoryScoreStore()
	score, _ := normalizeScore("run-1", "ind-1", ScoreInput{
		ScoreType: ScoreTypeExpert,
		Rater:     "评审员",
		Score:     intPtr(35),
	}, base, "score-1")
	if err := scoreStore.CreateScore(context.Background(), score); err != nil {
		t.Fatalf("CreateScore: %v", err)
	}

	service, _, _ := newFixtureReportService(source, indicatorStore, scoreStore, fixedClock())
	report, created, err := service.GenerateReport(context.Background(), "run-1")
	if err != nil || !created {
		t.Fatalf("GenerateReport: created=%v err=%v", created, err)
	}
	entry, ok := report.IndicatorScores["ind-1"]
	if !ok {
		t.Fatal("indicator ind-1 missing from indicator_scores")
	}
	if entry.Score != 35 || entry.Expert == nil || *entry.Expert != 35 {
		t.Errorf("indicator score = %+v, want {score:35, expert:35}", entry)
	}
	if report.DimensionScores[DimensionResponseSpeed].Score != 35 {
		t.Errorf("响应速度 = %v, want 35", report.DimensionScores[DimensionResponseSpeed].Score)
	}
	if report.OverallScore != 35 {
		t.Errorf("overall = %v, want 35", report.OverallScore)
	}
	wantText := "响应速度维度平均分 35.0 分，未达 60 分，建议优化预警发现、信息上报与预案启动流程，缩短应急响应用时。"
	if len(report.Suggestions) != 1 || report.Suggestions[0].Text != wantText || report.Suggestions[0].Level != SuggestionLevelSevere {
		t.Errorf("suggestions = %+v, want the 响应速度 severe template", report.Suggestions)
	}
}

// ─── GetReportByRun / ListReports ────────────────────────────────────

// TestGetReportByRun asserts the single-report lookup: the generated
// report is returned, a run without a report answers ErrReportNotFound.
func TestGetReportByRun(t *testing.T) {
	_, source := completedRunFixture()
	service, _, _ := newFixtureReportService(source, NewInMemoryStore(), NewInMemoryScoreStore(), fixedClock())

	if _, err := service.GetReportByRun(context.Background(), "run-1"); !errors.Is(err, ErrReportNotFound) {
		t.Fatalf("GetReportByRun before generation = %v, want ErrReportNotFound", err)
	}
	generated, _, err := service.GenerateReport(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GenerateReport: %v", err)
	}
	report, err := service.GetReportByRun(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetReportByRun: %v", err)
	}
	if report.ID != generated.ID || report.OverallScore != generated.OverallScore {
		t.Errorf("report = %+v, want the generated report %+v", report, generated)
	}
}

// TestListReportsSortFilterPagination asserts the list contract: the
// run_id filter, the created_at DESC / id DESC sort (newest first) and
// the limit/offset pagination with the total before pagination.
func TestListReportsSortFilterPagination(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	source := &fixtureReportSource{run: ReportRun{ID: "run-1", Status: reportRunStatusCompleted}}
	reportStore := NewInMemoryReportStore()
	service := NewReportService(reportStore, NewInMemoryStore(), NewInMemoryScoreStore(), source)
	ids := &idSequence{}
	service.newID = ids.next
	service.now = func() time.Time { return now }

	// run-1 与 run-2 同为 t1（id 依次 report-id-A / report-id-B，B > A），
	// run-3 为 t1-1h（最旧）。期望顺序：B(run-2), A(run-1), C(run-3)
	// —— created_at DESC，同时刻 id DESC tie-break。
	if _, _, err := service.GenerateReport(context.Background(), "run-1"); err != nil {
		t.Fatalf("generate run-1: %v", err)
	}
	if _, _, err := service.GenerateReport(context.Background(), "run-2"); err != nil {
		t.Fatalf("generate run-2: %v", err)
	}
	service.now = func() time.Time { return now.Add(-time.Hour) }
	if _, _, err := service.GenerateReport(context.Background(), "run-3"); err != nil {
		t.Fatalf("generate run-3: %v", err)
	}

	records, total, err := service.ListReports(context.Background(), ReportFilter{Limit: 10})
	if err != nil {
		t.Fatalf("ListReports: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("total/records = %d/%d, want 3/3", total, len(records))
	}
	wantOrder := []string{"run-2", "run-1", "run-3"}
	for i, want := range wantOrder {
		if records[i].RunID != want {
			t.Errorf("records[%d].run_id = %q, want %q (created_at DESC, id DESC tie-break)", i, records[i].RunID, want)
		}
	}

	// run_id 筛选：命中一条；未知 run_id 空列表。
	filtered, total, err := service.ListReports(context.Background(), ReportFilter{RunID: "run-2", Limit: 10})
	if err != nil || total != 1 || len(filtered) != 1 || filtered[0].RunID != "run-2" {
		t.Errorf("filtered = %d/%d (%+v), want 1/1 run-2", len(filtered), total, filtered)
	}
	empty, total, err := service.ListReports(context.Background(), ReportFilter{RunID: "unknown", Limit: 10})
	if err != nil || total != 0 || len(empty) != 0 {
		t.Errorf("unknown run_id = %d/%d, want 0/0 (err %v)", len(empty), total, err)
	}

	// 分页：limit=2 → 前两条；offset=2 → 最后一条；total 恒为 3。
	page, total, err := service.ListReports(context.Background(), ReportFilter{Limit: 2})
	if err != nil || total != 3 || len(page) != 2 {
		t.Fatalf("limit=2 = %d/%d, want 2/3", len(page), total)
	}
	if page[0].RunID != "run-2" || page[1].RunID != "run-1" {
		t.Errorf("limit=2 order = %q/%q, want run-2/run-1", page[0].RunID, page[1].RunID)
	}
	tail, total, err := service.ListReports(context.Background(), ReportFilter{Limit: 10, Offset: 2})
	if err != nil || total != 3 || len(tail) != 1 || tail[0].RunID != "run-3" {
		t.Errorf("offset=2 = %d/%d (%q), want 1/3 run-3", len(tail), total, tail[0].RunID)
	}
}

// TestReportStoreInvariants asserts the store-level rules: the unique
// run_id on create, the not-found errors and the run cascade deletion
// (DeleteReportsByRun).
func TestReportStoreInvariants(t *testing.T) {
	store := NewInMemoryReportStore()
	ctx := context.Background()
	report := Report{ID: "id-1", RunID: "run-1", OverallScore: 80}
	if err := store.CreateReport(ctx, report); err != nil {
		t.Fatalf("CreateReport: %v", err)
	}
	if err := store.CreateReport(ctx, Report{ID: "id-2", RunID: "run-1"}); !errors.Is(err, ErrReportExists) {
		t.Errorf("duplicate CreateReport = %v, want ErrReportExists (UNIQUE run_id)", err)
	}
	if err := store.UpdateReport(ctx, Report{ID: "id-x", RunID: "run-x"}); !errors.Is(err, ErrReportNotFound) {
		t.Errorf("UpdateReport of an unknown run = %v, want ErrReportNotFound", err)
	}
	if err := store.DeleteReportsByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteReportsByRun: %v", err)
	}
	if _, err := store.GetReportByRun(ctx, "run-1"); !errors.Is(err, ErrReportNotFound) {
		t.Errorf("GetReportByRun after cascade = %v, want ErrReportNotFound", err)
	}
	// 删除不存在的 run 也不是错误（幂等级联）。
	if err := store.DeleteReportsByRun(ctx, "run-1"); err != nil {
		t.Errorf("DeleteReportsByRun twice = %v, want nil", err)
	}
}
