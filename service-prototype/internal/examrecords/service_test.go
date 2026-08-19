package examrecords

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// testPaper builds the seeded paper the grading tests open exams on:
// pass_score 2 with one question of every type (单选 B / 多选 [A,C] /
// 判断 正确 / 填空 Java).
func testPaper() papers.Paper {
	return papers.Paper{
		ID:        "01ARZ3NDEKTSV4RRFFQ69G5FAV",
		Title:     "月度理论考核",
		PassScore: 2,
		Questions: []papers.QuestionSnapshot{
			{ID: "q-single", Type: questions.QuestionTypeSingle, Difficulty: 3, Content: "单选题目", Options: []string{"A", "B", "C"}, Answer: "B"},
			{ID: "q-multi", Type: questions.QuestionTypeMultiple, Difficulty: 3, Content: "多选题目", Options: []string{"A", "B", "C", "D"}, Answer: []any{"A", "C"}},
			{ID: "q-judge", Type: questions.QuestionTypeJudgment, Difficulty: 2, Content: "判断题目", Options: []string{}, Answer: "正确"},
			{ID: "q-fill", Type: questions.QuestionTypeFill, Difficulty: 2, Content: "填空题目", Options: []string{}, Answer: "Java"},
		},
		CreatedAt: testTime,
		UpdatedAt: testTime,
	}
}

// newTestService builds the exam-records service over fresh in-memory
// stores with the seeded paper; the paper store is injected as the
// PaperLookup, so everything stays in memory.
func newTestService(t *testing.T) (*Service, *InMemoryStore) {
	t.Helper()
	paperStore := papers.NewInMemoryStore()
	if err := paperStore.Create(context.Background(), testPaper()); err != nil {
		t.Fatalf("seed paper: %v", err)
	}
	store := NewInMemoryStore()
	return NewService(store, paperStore), store
}

// openExam opens an exam for the given employee on the seeded paper and
// returns the record; it fails the test on any error.
func openExam(t *testing.T, service *Service, employeeID string) Record {
	t.Helper()
	record, err := service.Create(context.Background(), Input{
		EmployeeID: employeeID,
		PaperID:    "01ARZ3NDEKTSV4RRFFQ69G5FAV",
		Metadata:   map[string]any{},
	})
	if err != nil {
		t.Fatalf("open exam: %v", err)
	}
	return record
}

// ─── Create ─────────────────────────────────────────────────────────

// Create 生成 26 位 ULID id，start_time=开考时刻，快照含 paper_id/
// pass_score 与每题 id/type/difficulty/content/options/answer；
// end_time/score/passed 为空，metadata/created_by 缺省 {} / ""。
func TestCreateSnapshotsThePaper(t *testing.T) {
	service, _ := newTestService(t)
	before := time.Now()
	record, err := service.Create(context.Background(), Input{
		EmployeeID: "01ARZ3NDEKTSV4RRFFQ69G5FAV",
		PaperID:    "01ARZ3NDEKTSV4RRFFQ69G5FAV",
		Metadata:   map[string]any{"source": "web"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if !ValidULID(record.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", record.ID)
	}
	if record.EmployeeID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || record.PaperID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" {
		t.Fatalf("record echoes employee/paper = %q/%q", record.EmployeeID, record.PaperID)
	}
	if record.StartTime.Before(before) {
		t.Fatalf("start_time = %v, want >= open time %v", record.StartTime, before)
	}
	if record.EndTime != nil || record.Score != nil || record.Passed != nil {
		t.Fatalf("end_time/score/passed must be null before submission, got %+v", record)
	}
	snapshot := record.AnswersSnapshot
	if snapshot.PaperID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || snapshot.PassScore != 2 {
		t.Fatalf("snapshot paper_id/pass_score = %q/%d, want paper id and 2", snapshot.PaperID, snapshot.PassScore)
	}
	if len(snapshot.Questions) != 4 {
		t.Fatalf("snapshot has %d questions, want 4", len(snapshot.Questions))
	}
	first := snapshot.Questions[0]
	if first.ID != "q-single" || first.Type != questions.QuestionTypeSingle ||
		first.Difficulty != 3 || first.Content != "单选题目" {
		t.Fatalf("snapshot question = %+v, want the projected paper question", first)
	}
	if len(first.Options) != 3 || first.Options[0] != "A" || first.Answer != "B" {
		t.Fatalf("snapshot options/answer = %v/%v, want the paper's", first.Options, first.Answer)
	}
	if record.Metadata["source"] != "web" || record.CreatedBy != "u-admin" {
		t.Fatalf("metadata/created_by = %v/%q, want echoed", record.Metadata, record.CreatedBy)
	}
	if record.CreatedAt.IsZero() || record.UpdatedAt.IsZero() {
		t.Fatal("created_at/updated_at must be set")
	}
}

// Create 缺 employee_id/paper_id 或 employee_id 非 26 位 ULID → 校验错
// 误；paper 不存在 → ErrPaperNotFound。
func TestCreateValidation(t *testing.T) {
	service, _ := newTestService(t)
	cases := []struct {
		name    string
		input   Input
		wantErr error
		want400 bool
	}{
		{"missing employee_id", Input{EmployeeID: "", PaperID: "01ARZ3NDEKTSV4RRFFQ69G5FAV"}, nil, true},
		{"blank employee_id", Input{EmployeeID: "  ", PaperID: "01ARZ3NDEKTSV4RRFFQ69G5FAV"}, nil, true},
		{"non-ULID employee_id", Input{EmployeeID: "not-a-ulid", PaperID: "01ARZ3NDEKTSV4RRFFQ69G5FAV"}, nil, true},
		{"missing paper_id", Input{EmployeeID: "01ARZ3NDEKTSV4RRFFQ69G5FAV", PaperID: ""}, nil, true},
		{"unknown paper", Input{EmployeeID: "01ARZ3NDEKTSV4RRFFQ69G5FAV", PaperID: "01ARZ3NDEKTSV4RRFFQ69G5FA1"}, ErrPaperNotFound, false},
	}
	for _, tc := range cases {
		_, err := service.Create(context.Background(), tc.input)
		if tc.want400 {
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				t.Fatalf("%s: error = %v, want ValidationError", tc.name, err)
			}
			continue
		}
		if !errors.Is(err, tc.wantErr) {
			t.Fatalf("%s: error = %v, want %v", tc.name, err, tc.wantErr)
		}
	}
}

// ─── Submit 判分 ────────────────────────────────────────────────────

// 四题全对：score = 4（每题 1 分，依赖卡未交付题分值），passed =
// 4 >= 2，end_time 非空且 >= start_time，快照不变。
func TestSubmitAllCorrect(t *testing.T) {
	service, _ := newTestService(t)
	record := openExam(t, service, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	finished, err := service.Submit(context.Background(), record.ID, map[string]any{
		"q-single": "B",
		"q-multi":  []any{"A", "C"},
		"q-judge":  "正确",
		"q-fill":   "Java",
	})
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	if finished.Score == nil || *finished.Score != 4 {
		t.Fatalf("score = %v, want 4", finished.Score)
	}
	if finished.Passed == nil || !*finished.Passed {
		t.Fatalf("passed = %v, want true", finished.Passed)
	}
	if finished.EndTime == nil || finished.EndTime.Before(finished.StartTime) {
		t.Fatalf("end_time = %v, want >= start_time %v", finished.EndTime, finished.StartTime)
	}
	if finished.AnswersSnapshot.PassScore != 2 || len(finished.AnswersSnapshot.Questions) != 4 {
		t.Fatal("answers_snapshot must stay unchanged by submission")
	}
}

// 单选/判断/填空精确匹配才得分：错值（选项外值、判断传"对"、填空不
// 一致）得 0 分；多选全对才得分：少选/多选/错选均不得分、顺序无关。
func TestSubmitGradingRules(t *testing.T) {
	service, _ := newTestService(t)
	cases := []struct {
		name     string
		answers  map[string]any
		wantPass bool
	}{
		{
			"single exact match scores, wrong option does not",
			map[string]any{"q-single": "B", "q-multi": []any{"A", "C"}, "q-judge": "正确", "q-fill": "Java"},
			true,
		},
		{
			"single wrong option earns 0",
			map[string]any{"q-single": "A", "q-multi": []any{"A", "C"}, "q-judge": "正确", "q-fill": "Java"},
			true, // score 3 >= 2
		},
		{
			"judgment wrong value earns 0",
			map[string]any{"q-single": "B", "q-multi": []any{"A", "C"}, "q-judge": "对", "q-fill": "Java"},
			true,
		},
		{
			"fill mismatch earns 0",
			map[string]any{"q-single": "B", "q-multi": []any{"A", "C"}, "q-judge": "正确", "q-fill": "java"},
			true,
		},
		{
			"multiple exact set scores, order free",
			map[string]any{"q-single": "B", "q-multi": []any{"C", "A"}, "q-judge": "正确", "q-fill": "Java"},
			true,
		},
		{
			"multiple missing one option earns 0 (少选)",
			map[string]any{"q-single": "B", "q-multi": []any{"A"}, "q-judge": "正确", "q-fill": "Java"},
			true, // score 3 >= 2
		},
		{
			"multiple extra option earns 0 (多选)",
			map[string]any{"q-single": "B", "q-multi": []any{"A", "C", "D"}, "q-judge": "正确", "q-fill": "Java"},
			true, // score 3 >= 2
		},
		{
			"multiple wrong option earns 0 (错选)",
			map[string]any{"q-single": "B", "q-multi": []any{"A", "B"}, "q-judge": "正确", "q-fill": "Java"},
			true, // score 3 >= 2
		},
		{
			"only one correct question: passed = score >= pass_score fails by one",
			map[string]any{"q-single": "B"},
			false, // score 1, pass_score 2
		},
		{
			"empty answers map earns 0 and completes",
			map[string]any{},
			false,
		},
		{
			"missing question is 漏答: 0 points, submission completes",
			map[string]any{"q-single": "B", "q-judge": "正确"},
			true, // score 2 == pass_score 2, boundary passes
		},
	}
	for _, tc := range cases {
		record := openExam(t, service, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
		finished, err := service.Submit(context.Background(), record.ID, tc.answers)
		if err != nil {
			t.Fatalf("%s: submit: %v", tc.name, err)
		}
		if finished.EndTime == nil {
			t.Fatalf("%s: submission must complete normally", tc.name)
		}
		if finished.Passed == nil || *finished.Passed != tc.wantPass {
			t.Fatalf("%s: passed = %v, want %v (score %v, pass_score 2)", tc.name, finished.Passed, tc.wantPass, finished.Score)
		}
	}
}

// passed 边界：score == pass_score 通过、差一分不通过；pass_score 0 时
// 空答卷也通过。
func TestSubmitPassedBoundary(t *testing.T) {
	service, _ := newTestService(t)
	// score 2 == pass_score 2 → 通过。
	record := openExam(t, service, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	finished, err := service.Submit(context.Background(), record.ID, map[string]any{"q-single": "B", "q-judge": "正确"})
	if err != nil {
		t.Fatalf("submit boundary: %v", err)
	}
	if finished.Passed == nil || !*finished.Passed || *finished.Score != 2 {
		t.Fatalf("boundary: score = %v, passed = %v, want 2/true", finished.Score, finished.Passed)
	}
	// 差一分：score 1 < 2 → 不通过。
	record = openExam(t, service, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	finished, err = service.Submit(context.Background(), record.ID, map[string]any{"q-single": "B"})
	if err != nil {
		t.Fatalf("submit below boundary: %v", err)
	}
	if finished.Passed == nil || *finished.Passed || *finished.Score != 1 {
		t.Fatalf("below boundary: score = %v, passed = %v, want 1/false", finished.Score, finished.Passed)
	}
	// pass_score 0：空答卷 score 0 >= 0 → 通过。
	paperStore := papers.NewInMemoryStore()
	zeroPaper := testPaper()
	zeroPaper.PassScore = 0
	zeroPaper.Questions = []papers.QuestionSnapshot{}
	if err := paperStore.Create(context.Background(), zeroPaper); err != nil {
		t.Fatalf("seed zero paper: %v", err)
	}
	zeroService := NewService(NewInMemoryStore(), paperStore)
	record, err = zeroService.Create(context.Background(), Input{EmployeeID: "01ARZ3NDEKTSV4RRFFQ69G5FAV", PaperID: "01ARZ3NDEKTSV4RRFFQ69G5FAV"})
	if err != nil {
		t.Fatalf("open zero-paper exam: %v", err)
	}
	finished, err = zeroService.Submit(context.Background(), record.ID, map[string]any{})
	if err != nil {
		t.Fatalf("submit zero-paper exam: %v", err)
	}
	if finished.Score == nil || *finished.Score != 0 || finished.Passed == nil || !*finished.Passed {
		t.Fatalf("zero pass_score: score = %v, passed = %v, want 0/true", finished.Score, finished.Passed)
	}
}

// 形状不符 → ValidationError：单选/判断/填空传数组或空串、多选传字符
// 串、空数组或含非字符串元素；未知题目 id → ValidationError；记录不
// 存在 → ErrNotFound；重复交卷 → ErrAlreadySubmitted。校验失败后记录
// 保持未交卷状态。
func TestSubmitErrors(t *testing.T) {
	service, _ := newTestService(t)
	shapeCases := []struct {
		name    string
		answers map[string]any
	}{
		{"single as array", map[string]any{"q-single": []any{"B"}}},
		{"single empty string", map[string]any{"q-single": ""}},
		{"judgment empty string", map[string]any{"q-judge": ""}},
		{"fill empty string", map[string]any{"q-fill": ""}},
		{"fill as number", map[string]any{"q-fill": 42}},
		{"multiple as string", map[string]any{"q-multi": "A"}},
		{"multiple empty array", map[string]any{"q-multi": []any{}}},
		{"multiple with non-string element", map[string]any{"q-multi": []any{"A", 1}}},
		{"unknown question id", map[string]any{"q-unknown": "B"}},
	}
	for _, tc := range shapeCases {
		record := openExam(t, service, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
		_, err := service.Submit(context.Background(), record.ID, tc.answers)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: error = %v, want ValidationError", tc.name, err)
		}
		// 校验失败后记录仍未交卷。
		unchanged, err := service.Get(context.Background(), record.ID)
		if err != nil {
			t.Fatalf("%s: get: %v", tc.name, err)
		}
		if unchanged.EndTime != nil || unchanged.Score != nil || unchanged.Passed != nil {
			t.Fatalf("%s: failed submit must not write end_time/score/passed", tc.name)
		}
	}

	// 记录不存在。
	_, err := service.Submit(context.Background(), "missing", map[string]any{})
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown record: error = %v, want ErrNotFound", err)
	}

	// 重复交卷。
	record := openExam(t, service, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	if _, err := service.Submit(context.Background(), record.ID, map[string]any{}); err != nil {
		t.Fatalf("first submit: %v", err)
	}
	_, err = service.Submit(context.Background(), record.ID, map[string]any{"q-single": "B"})
	if !errors.Is(err, ErrAlreadySubmitted) {
		t.Fatalf("second submit: error = %v, want ErrAlreadySubmitted", err)
	}
}
