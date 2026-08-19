package evaluation

import (
	"context"
	"errors"
	"testing"
	"time"
)

// errFixtureRunMissing is the not-found error the fixture run checker
// answers for unknown runs (the routing layer maps the drills
// ErrRunNotFound of the real checker to 404; the service only
// propagates the injected error).
var errFixtureRunMissing = errors.New("drill not found")

// fixtureRunChecker is the test fixture behind the RunExistenceChecker
// interface: it accepts exactly the run ids of the fixture.
type fixtureRunChecker struct {
	runs map[string]bool
}

func newFixtureRunChecker(ids ...string) *fixtureRunChecker {
	runs := make(map[string]bool, len(ids))
	for _, id := range ids {
		runs[id] = true
	}
	return &fixtureRunChecker{runs: runs}
}

func (f *fixtureRunChecker) RunExists(_ context.Context, runID string) error {
	if f.runs[runID] {
		return nil
	}
	return errFixtureRunMissing
}

// createFixtureIndicator creates an indicator through a real evaluation
// service over the given store and returns it (the score fixture needs
// an existing indicator for the existence check).
func createFixtureIndicator(t *testing.T, indicatorStore Store, title string) Indicator {
	t.Helper()
	indicatorService := NewService(indicatorStore)
	indicator, err := indicatorService.CreateIndicator(context.Background(), IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     title,
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}
	return indicator
}

// scoreFixture builds a score service over fresh in-memory stores with a
// run and an indicator in place, and returns the service, the indicator
// store (for extra fixture indicators), the run id and the indicator id.
func scoreFixture(t *testing.T) (*ScoreService, Store, string, string) {
	t.Helper()
	scoreStore := NewInMemoryScoreStore()
	indicatorStore := NewInMemoryStore()
	service := NewScoreService(scoreStore, indicatorStore, newFixtureRunChecker("run-1", "run-2"))
	indicator := createFixtureIndicator(t, indicatorStore, "预警响应速度")
	return service, indicatorStore, "run-1", indicator.ID
}

// scorePtr returns a pointer to the score value (the input field is a
// pointer so a missing field can be told apart from an explicit 0).
func scorePtr(score int) *int { return &score }

// TestScoreCreateDefaults: an expert score create applies the domain
// rules — required rater (trimmed), target forced to an empty string no
// matter what the request carries, score and comment echoed, created_by
// defaulting to ” — and returns a server-generated 26-character
// Crockford Base32 ULID with server timestamps.
func TestScoreCreateDefaults(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	score, err := service.CreateScore(context.Background(), runID, ScoreInput{
		IndicatorID: indicatorID,
		ScoreType:   ScoreTypeExpert,
		Rater:       "  评审专家A  ",
		Target:      "不应存在的目标",
		Score:       scorePtr(92),
		Comment:     "处置得当",
		CreatedBy:   "admin",
	})
	if err != nil {
		t.Fatalf("CreateScore: %v", err)
	}
	if !ulidPattern.MatchString(score.ID) {
		t.Errorf("id = %q, want a 26-character Crockford Base32 ULID", score.ID)
	}
	if score.RunID != runID || score.IndicatorID != indicatorID {
		t.Errorf("run/indicator = %q/%q, want %q/%q", score.RunID, score.IndicatorID, runID, indicatorID)
	}
	if score.ScoreType != ScoreTypeExpert {
		t.Errorf("score_type = %q, want 专家评分", score.ScoreType)
	}
	if score.Rater != "评审专家A" {
		t.Errorf("rater = %q, want the trimmed value", score.Rater)
	}
	if score.Target != "" {
		t.Errorf("target = %q, want an empty string (forced for 专家评分)", score.Target)
	}
	if score.Score != 92 {
		t.Errorf("score = %d, want 92", score.Score)
	}
	if score.Comment != "处置得当" || score.CreatedBy != "admin" {
		t.Errorf("comment/created_by = %q/%q, want the explicit values", score.Comment, score.CreatedBy)
	}
	if score.CreatedAt.IsZero() || !score.CreatedAt.Equal(score.UpdatedAt) {
		t.Errorf("timestamps = %v/%v, want set and equal", score.CreatedAt, score.UpdatedAt)
	}
}

// TestScoreCreateTargetPassthrough: 自评/互评 scores carry the target
// through (trimmed), the required rater and score, and the ” defaults
// for comment and created_by.
func TestScoreCreateTargetPassthrough(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	for _, tc := range []struct {
		name      string
		scoreType ScoreType
		target    string
		want      string
	}{
		{"self", ScoreTypeSelf, "  班组A  ", "班组A"},
		{"peer", ScoreTypePeer, "互评组B", "互评组B"},
	} {
		score, err := service.CreateScore(context.Background(), runID, ScoreInput{
			IndicatorID: indicatorID,
			ScoreType:   tc.scoreType,
			Rater:       "值班员1",
			Target:      tc.target,
			Score:       scorePtr(80),
		})
		if err != nil {
			t.Fatalf("CreateScore(%s): %v", tc.name, err)
		}
		if score.Target != tc.want {
			t.Errorf("%s: target = %q, want the passthrough value %q", tc.name, score.Target, tc.want)
		}
		if score.Comment != "" || score.CreatedBy != "" {
			t.Errorf("%s: comment/created_by = %q/%q, want empty defaults", tc.name, score.Comment, score.CreatedBy)
		}
	}
}

// TestScoreCreateValidation: create rejects an invalid score_type, a
// missing/blank rater, a missing score, a score outside 0-100 and a
// missing/blank target for 自评/互评 with a ValidationError (mapped to
// HTTP 400).
func TestScoreCreateValidation(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()

	cases := []struct {
		name  string
		input ScoreInput
	}{
		{"invalid score_type", ScoreInput{IndicatorID: indicatorID, ScoreType: "其他", Rater: "r", Target: "t", Score: scorePtr(80)}},
		{"missing score_type", ScoreInput{IndicatorID: indicatorID, ScoreType: "", Rater: "r", Target: "t", Score: scorePtr(80)}},
		{"missing rater", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Target: "t", Score: scorePtr(80)}},
		{"blank rater", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "   ", Target: "t", Score: scorePtr(80)}},
		{"missing score", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "r", Target: "t"}},
		{"score below zero", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "r", Target: "t", Score: scorePtr(-1)}},
		{"score above 100", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "r", Target: "t", Score: scorePtr(101)}},
		{"self missing target", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "r", Score: scorePtr(80)}},
		{"self blank target", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "r", Target: "  ", Score: scorePtr(80)}},
		{"peer missing target", ScoreInput{IndicatorID: indicatorID, ScoreType: ScoreTypePeer, Rater: "r", Score: scorePtr(80)}},
	}
	for _, tc := range cases {
		_, err := service.CreateScore(ctx, runID, tc.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Errorf("%s: err = %v, want a ValidationError", tc.name, err)
		}
	}
}

// TestScoreCreateNotFound: a missing run and a missing indicator are
// returned unchanged (404 at the routing layer).
func TestScoreCreateNotFound(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()

	_, err := service.CreateScore(ctx, "run-missing", ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "r", Target: "t", Score: scorePtr(80),
	})
	if !errors.Is(err, errFixtureRunMissing) {
		t.Errorf("unknown run: err = %v, want the injected run error", err)
	}

	_, err = service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: "06G01KFRJTE84EP3234FBD2YD4", ScoreType: ScoreTypeSelf, Rater: "r", Target: "t", Score: scorePtr(80),
	})
	if !errors.Is(err, ErrIndicatorNotFound) {
		t.Errorf("unknown indicator: err = %v, want ErrIndicatorNotFound", err)
	}
}

// TestScoreExpertDuplicate: a second 专家评分 for the same (run,
// indicator) pair is rejected with ErrExpertScoreExists (message 该演练
// 与指标下已存在专家评分，请用 PUT 更新); 自评/互评 allow multiple records
// and coexist with the expert score.
func TestScoreExpertDuplicate(t *testing.T) {
	service, indicatorStore, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()

	if _, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeExpert, Rater: "评审专家A", Score: scorePtr(92),
	}); err != nil {
		t.Fatalf("CreateScore expert: %v", err)
	}
	_, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeExpert, Rater: "评审专家B", Score: scorePtr(95),
	})
	if !errors.Is(err, ErrExpertScoreExists) {
		t.Fatalf("duplicate expert: err = %v, want ErrExpertScoreExists", err)
	} else if err.Error() != "该演练与指标下已存在专家评分，请用 PUT 更新" {
		t.Errorf("error message = %q, want 该演练与指标下已存在专家评分，请用 PUT 更新", err.Error())
	}

	// 自评/互评多条不查重，并与专家评分共存。
	for _, scoreType := range []ScoreType{ScoreTypeSelf, ScoreTypeSelf, ScoreTypePeer} {
		if _, err := service.CreateScore(ctx, runID, ScoreInput{
			IndicatorID: indicatorID, ScoreType: scoreType, Rater: "值班员", Target: "班组A", Score: scorePtr(80),
		}); err != nil {
			t.Fatalf("CreateScore(%s): %v", scoreType, err)
		}
	}
	records, total, err := service.ListScores(ctx, runID, ScoreFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListScores: %v", err)
	}
	if total != 4 || len(records) != 4 {
		t.Fatalf("total/records = %d/%d, want 4/4 (one expert + three self/peer)", total, len(records))
	}

	// 同一 run 的另一指标不受影响：专家评分可再建。
	indicator2 := createFixtureIndicator(t, indicatorStore, "力量到场速度")
	if _, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicator2.ID, ScoreType: ScoreTypeExpert, Rater: "评审专家A", Score: scorePtr(88),
	}); err != nil {
		t.Fatalf("expert score of another indicator: %v", err)
	}
}

// TestScoreUpdateSemantics: PUT semantics — the fields of the request
// are applied, omitted comment/created_by are reset to their defaults,
// created_at is preserved and updated_at is refreshed; the id, run_id
// and indicator_id never change.
func TestScoreUpdateSemantics(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()
	created, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeExpert, Rater: "评审专家A",
		Score: scorePtr(92), Comment: "处置得当", CreatedBy: "admin",
	})
	if err != nil {
		t.Fatalf("CreateScore: %v", err)
	}

	time.Sleep(2 * time.Millisecond)
	updated, err := service.UpdateScore(ctx, runID, created.ID, ScoreInput{
		ScoreType: ScoreTypeExpert, Rater: "评审专家B", Score: scorePtr(95),
	})
	if err != nil {
		t.Fatalf("UpdateScore: %v", err)
	}
	if updated.ID != created.ID || updated.RunID != runID || updated.IndicatorID != indicatorID {
		t.Errorf("id/run/indicator = %q/%q/%q, want preserved %q/%q/%q", updated.ID, updated.RunID, updated.IndicatorID, created.ID, runID, indicatorID)
	}
	if updated.Rater != "评审专家B" || updated.Score != 95 {
		t.Errorf("rater/score = %q/%d, want the new values", updated.Rater, updated.Score)
	}
	if updated.Comment != "" || updated.CreatedBy != "" {
		t.Errorf("comment/created_by = %q/%q, want empty (omitted fields reset)", updated.Comment, updated.CreatedBy)
	}
	if updated.Target != "" {
		t.Errorf("target = %q, want empty (forced for 专家评分)", updated.Target)
	}
	if !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Errorf("created_at = %v, want %v (preserved)", updated.CreatedAt, created.CreatedAt)
	}
	if !updated.UpdatedAt.After(created.UpdatedAt) {
		t.Errorf("updated_at = %v, want later than %v (refreshed)", updated.UpdatedAt, created.UpdatedAt)
	}

	// The GET path reflects the update.
	got, err := service.GetScore(ctx, runID, created.ID)
	if err != nil {
		t.Fatalf("GetScore: %v", err)
	}
	if got.Rater != "评审专家B" || got.Score != 95 {
		t.Errorf("GET after PUT = %q/%d, want the updated values", got.Rater, got.Score)
	}
}

// TestScoreUpdateValidation: update rejects the same invalid inputs as
// create; a missing run or a missing score id are not-found errors.
func TestScoreUpdateValidation(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()
	created, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypePeer, Rater: "r", Target: "t", Score: scorePtr(80),
	})
	if err != nil {
		t.Fatalf("CreateScore: %v", err)
	}

	cases := []struct {
		name  string
		runID string
		id    string
		input ScoreInput
	}{
		{"invalid score_type", runID, created.ID, ScoreInput{ScoreType: "其他", Rater: "r", Target: "t", Score: scorePtr(80)}},
		{"missing rater", runID, created.ID, ScoreInput{ScoreType: ScoreTypePeer, Rater: "  ", Target: "t", Score: scorePtr(80)}},
		{"missing score", runID, created.ID, ScoreInput{ScoreType: ScoreTypePeer, Rater: "r", Target: "t"}},
		{"score below zero", runID, created.ID, ScoreInput{ScoreType: ScoreTypePeer, Rater: "r", Target: "t", Score: scorePtr(-1)}},
		{"peer missing target", runID, created.ID, ScoreInput{ScoreType: ScoreTypePeer, Rater: "r", Score: scorePtr(80)}},
		{"unknown score id", runID, "06G01KFRJTE84EP3234FBD2YD4", ScoreInput{ScoreType: ScoreTypePeer, Rater: "r", Target: "t", Score: scorePtr(80)}},
		{"unknown run", "run-missing", created.ID, ScoreInput{ScoreType: ScoreTypePeer, Rater: "r", Target: "t", Score: scorePtr(80)}},
	}
	for _, tc := range cases {
		_, err := service.UpdateScore(ctx, tc.runID, tc.id, tc.input)
		switch tc.name {
		case "unknown score id":
			if !errors.Is(err, ErrScoreNotFound) {
				t.Errorf("%s: err = %v, want ErrScoreNotFound", tc.name, err)
			}
		case "unknown run":
			if !errors.Is(err, errFixtureRunMissing) {
				t.Errorf("%s: err = %v, want the injected run error", tc.name, err)
			}
		default:
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				t.Errorf("%s: err = %v, want a ValidationError", tc.name, err)
			}
		}
	}
}

// TestScoreUpdateExpertConflict: updating a record to 专家评分 conflicts
// with the expert score of the same (run, indicator) pair excluding the
// record itself; updating the expert score in place and updating
// self/peer records (to any non-expert type) never conflict.
func TestScoreUpdateExpertConflict(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()

	expert, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeExpert, Rater: "评审专家A", Score: scorePtr(92),
	})
	if err != nil {
		t.Fatalf("CreateScore expert: %v", err)
	}
	self, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "值班员", Target: "班组A", Score: scorePtr(80),
	})
	if err != nil {
		t.Fatalf("CreateScore self: %v", err)
	}

	// PUT 将自评改为专家评分：与既有专家评分冲突（排除自身后仍存在）→ 400。
	_, err = service.UpdateScore(ctx, runID, self.ID, ScoreInput{
		ScoreType: ScoreTypeExpert, Rater: "值班员", Score: scorePtr(85),
	})
	if !errors.Is(err, ErrExpertScoreExists) {
		t.Fatalf("self -> expert: err = %v, want ErrExpertScoreExists", err)
	}

	// 专家评分原地 PUT（排除自身）不冲突。
	if _, err := service.UpdateScore(ctx, runID, expert.ID, ScoreInput{
		ScoreType: ScoreTypeExpert, Rater: "评审专家A", Score: scorePtr(95),
	}); err != nil {
		t.Fatalf("expert in place: %v", err)
	}

	// 自评改互评不冲突。
	if _, err := service.UpdateScore(ctx, runID, self.ID, ScoreInput{
		ScoreType: ScoreTypePeer, Rater: "值班员", Target: "班组B", Score: scorePtr(82),
	}); err != nil {
		t.Fatalf("self -> peer: %v", err)
	}
}

// TestScoreListEmpty: the empty store returns zero records and a zero
// total for an existing run.
func TestScoreListEmpty(t *testing.T) {
	service, _, runID, _ := scoreFixture(t)
	records, total, err := service.ListScores(context.Background(), runID, ScoreFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListScores: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("total/records = %d/%d, want 0/0", total, len(records))
	}
}

// TestScoreListFilterPaginationSort: the score_type and indicator_id
// filters narrow the match (combined too), limit/offset paginate (the
// total stays the pre-pagination count) and the sort order is
// created_at ASC, id ASC. The clock is fixed so the created_at
// tie-break is exercised: with identical timestamps the rows must come
// back ordered by id.
func TestScoreListFilterPaginationSort(t *testing.T) {
	service, indicatorStore, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()
	indicator2 := createFixtureIndicator(t, indicatorStore, "处置流程规范性")

	fixed := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return fixed }

	// 三条同时间戳记录（created_at 相同，排序退化为 id 升序）+ 一条
	// 不同指标/类型的记录。
	scores := []ScoreInput{
		{IndicatorID: indicatorID, ScoreType: ScoreTypeExpert, Rater: "专家A", Score: scorePtr(92)},
		{IndicatorID: indicatorID, ScoreType: ScoreTypeSelf, Rater: "值班员", Target: "班组A", Score: scorePtr(80)},
		{IndicatorID: indicatorID, ScoreType: ScoreTypePeer, Rater: "互评员", Target: "班组B", Score: scorePtr(85)},
		{IndicatorID: indicator2.ID, ScoreType: ScoreTypeExpert, Rater: "专家B", Score: scorePtr(88)},
	}
	created := make([]Score, 0, len(scores))
	for _, input := range scores {
		score, err := service.CreateScore(ctx, runID, input)
		if err != nil {
			t.Fatalf("CreateScore(%s): %v", input.Rater, err)
		}
		created = append(created, score)
	}

	// 全量列表：4 条，created_at 相同 → id 升序（tie-break 被真实触发）。
	records, total, err := service.ListScores(ctx, runID, ScoreFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListScores: %v", err)
	}
	if total != 4 || len(records) != 4 {
		t.Fatalf("total/records = %d/%d, want 4/4", total, len(records))
	}
	for _, score := range records {
		if !score.CreatedAt.Equal(fixed) {
			t.Fatalf("created_at = %v, want the fixed clock %v (the tie-break must decide the order)", score.CreatedAt, fixed)
		}
	}
	createdIDs := make(map[string]bool, len(created))
	for _, score := range created {
		createdIDs[score.ID] = true
	}
	seen := make(map[string]bool, len(records))
	for i := 1; i < len(records); i++ {
		if records[i-1].ID >= records[i].ID {
			t.Fatalf("tie-break order diverges at %d: %q >= %q, want strictly ascending ids", i, records[i-1].ID, records[i].ID)
		}
	}
	for _, score := range records {
		if !createdIDs[score.ID] || seen[score.ID] {
			t.Fatalf("record id %q is not exactly one of the created ids", score.ID)
		}
		seen[score.ID] = true
	}

	// score_type 筛选：只回 自评。
	filtered, filteredTotal, err := service.ListScores(ctx, runID, ScoreFilter{ScoreType: ScoreTypeSelf, Limit: 50})
	if err != nil {
		t.Fatalf("ListScores(score_type): %v", err)
	}
	if filteredTotal != 1 || len(filtered) != 1 || filtered[0].Rater != "值班员" {
		t.Fatalf("score_type filter = %d/%d (rater %q), want 1/1 (值班员)", filteredTotal, len(filtered), filtered[0].Rater)
	}

	// indicator_id 筛选：只回第二条指标的专家评分。
	filtered, filteredTotal, err = service.ListScores(ctx, runID, ScoreFilter{IndicatorID: indicator2.ID, Limit: 50})
	if err != nil {
		t.Fatalf("ListScores(indicator_id): %v", err)
	}
	if filteredTotal != 1 || len(filtered) != 1 || filtered[0].Score != 88 {
		t.Fatalf("indicator_id filter = %d/%d (score %d), want 1/1 (88)", filteredTotal, len(filtered), filtered[0].Score)
	}

	// 组合筛选：指标一 + 专家评分 → 1 条。
	combined, combinedTotal, err := service.ListScores(ctx, runID, ScoreFilter{ScoreType: ScoreTypeExpert, IndicatorID: indicatorID, Limit: 50})
	if err != nil {
		t.Fatalf("ListScores(combined): %v", err)
	}
	if combinedTotal != 1 || len(combined) != 1 || combined[0].Rater != "专家A" {
		t.Fatalf("combined filter = %d/%d (rater %q), want 1/1 (专家A)", combinedTotal, len(combined), combined[0].Rater)
	}

	// 分页：limit=2&offset=1 回第 2..3 条，total 仍为 4。
	page, pageTotal, err := service.ListScores(ctx, runID, ScoreFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListScores(page): %v", err)
	}
	if pageTotal != 4 || len(page) != 2 {
		t.Fatalf("page total/records = %d/%d, want 4/2", pageTotal, len(page))
	}
	if page[0].ID != records[1].ID || page[1].ID != records[2].ID {
		t.Errorf("page ids = %q..%q, want the second and third rows", page[0].ID, page[1].ID)
	}
}

// TestScoreGetDeleteScopedByRun: get and delete are scoped to the run
// of the route path — the same id under another run is not found.
func TestScoreGetDeleteScopedByRun(t *testing.T) {
	service, _, runID, indicatorID := scoreFixture(t)
	ctx := context.Background()
	created, err := service.CreateScore(ctx, runID, ScoreInput{
		IndicatorID: indicatorID, ScoreType: ScoreTypeExpert, Rater: "专家A", Score: scorePtr(92),
	})
	if err != nil {
		t.Fatalf("CreateScore: %v", err)
	}

	if _, err := service.GetScore(ctx, "run-2", created.ID); !errors.Is(err, ErrScoreNotFound) {
		t.Errorf("GetScore other run: err = %v, want ErrScoreNotFound", err)
	}
	if _, err := service.GetScore(ctx, "run-missing", created.ID); !errors.Is(err, errFixtureRunMissing) {
		t.Errorf("GetScore missing run: err = %v, want the injected run error", err)
	}
	if err := service.DeleteScore(ctx, "run-2", created.ID); !errors.Is(err, ErrScoreNotFound) {
		t.Errorf("DeleteScore other run: err = %v, want ErrScoreNotFound", err)
	}
	if err := service.DeleteScore(ctx, runID, created.ID); err != nil {
		t.Fatalf("DeleteScore: %v", err)
	}
	if _, err := service.GetScore(ctx, runID, created.ID); !errors.Is(err, ErrScoreNotFound) {
		t.Errorf("GET after DELETE: err = %v, want ErrScoreNotFound", err)
	}
	if err := service.DeleteScore(ctx, runID, created.ID); !errors.Is(err, ErrScoreNotFound) {
		t.Errorf("DELETE again: err = %v, want ErrScoreNotFound", err)
	}
}

// TestScoreStoreCascadeAndReferenceSource: DeleteScoresByRun removes
// only the scores of the given run (the in-memory counterpart of the
// DB's ON DELETE CASCADE, wired into the drills service); the store is
// also the real ScoreRefChecker source — CountScoresByIndicator counts
// the referencing scores, so the indicator service can reject the
// deletion of a referenced indicator.
func TestScoreStoreCascadeAndReferenceSource(t *testing.T) {
	store := NewInMemoryScoreStore()
	indicatorStore := NewInMemoryStore()
	service := NewScoreService(store, indicatorStore, newFixtureRunChecker("run-1", "run-2"))
	ctx := context.Background()
	indicator := createFixtureIndicator(t, indicatorStore, "预警响应速度")

	scoreA1, err := service.CreateScore(ctx, "run-1", ScoreInput{
		IndicatorID: indicator.ID, ScoreType: ScoreTypeExpert, Rater: "专家A", Score: scorePtr(92),
	})
	if err != nil {
		t.Fatalf("CreateScore A1: %v", err)
	}
	if _, err := service.CreateScore(ctx, "run-1", ScoreInput{
		IndicatorID: indicator.ID, ScoreType: ScoreTypeSelf, Rater: "值班员", Target: "班组A", Score: scorePtr(80),
	}); err != nil {
		t.Fatalf("CreateScore A2: %v", err)
	}
	scoreB, err := service.CreateScore(ctx, "run-2", ScoreInput{
		IndicatorID: indicator.ID, ScoreType: ScoreTypePeer, Rater: "互评员", Target: "班组B", Score: scorePtr(85),
	})
	if err != nil {
		t.Fatalf("CreateScore B: %v", err)
	}

	// 引用源：三张评分都引用该指标。
	count, err := store.CountScoresByIndicator(ctx, indicator.ID)
	if err != nil {
		t.Fatalf("CountScoresByIndicator: %v", err)
	}
	if count != 3 {
		t.Fatalf("reference count = %d, want 3", count)
	}

	// 级联删除 run-1：其评分清空，run-2 的保留。
	if err := store.DeleteScoresByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteScoresByRun: %v", err)
	}
	records, total, err := store.ListScoresByRun(ctx, "run-1", ScoreFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListScoresByRun run-1: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("run-1 after cascade: total/records = %d/%d, want 0/0", total, len(records))
	}
	if _, err := store.GetScore(ctx, "run-1", scoreA1.ID); !errors.Is(err, ErrScoreNotFound) {
		t.Errorf("run-1 score after cascade: err = %v, want ErrScoreNotFound", err)
	}
	if _, err := store.GetScore(ctx, "run-2", scoreB.ID); err != nil {
		t.Errorf("run-2 score must survive the cascade: %v", err)
	}

	// 引用数随之下降。
	count, err = store.CountScoresByIndicator(ctx, indicator.ID)
	if err != nil {
		t.Fatalf("CountScoresByIndicator after cascade: %v", err)
	}
	if count != 1 {
		t.Fatalf("reference count after cascade = %d, want 1", count)
	}
}
