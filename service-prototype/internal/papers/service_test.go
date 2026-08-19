package papers

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// fakeSource is an in-memory QuestionSource: it returns the questions of
// each type from a fixed map, so the generation logic is exercised
// without any storage.
type fakeSource struct {
	byType map[questions.QuestionType][]questions.Question
}

func (f *fakeSource) ListByType(_ context.Context, questionType questions.QuestionType) ([]questions.Question, error) {
	return append([]questions.Question(nil), f.byType[questionType]...), nil
}

// bankQuestions builds count questions of the given type with distinct
// ids.
func bankQuestions(count int, questionType questions.QuestionType) []questions.Question {
	bank := make([]questions.Question, 0, count)
	for i := 0; i < count; i++ {
		bank = append(bank, questions.Question{
			ID:         fmt.Sprintf("%s-%d", questionType, i),
			Type:       questionType,
			Difficulty: 1,
			Content:    fmt.Sprintf("题目 %d", i),
			Options:    []string{"A", "B"},
			Answer:     "A",
		})
	}
	return bank
}

func intPtr(value int) *int { return &value }

// paperInput builds a valid creation/update input.
func paperInput(title string, durationMinutes, passScore int, strategy map[string]any) Input {
	return Input{
		Title:              title,
		DurationMinutes:    intPtr(durationMinutes),
		PassScore:          intPtr(passScore),
		GenerationStrategy: strategy,
	}
}

// mustCreate creates a paper through the service and fails the test on
// error.
func mustCreate(t *testing.T, service *Service, input Input) Paper {
	t.Helper()
	paper, err := service.Create(context.Background(), input)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	return paper
}

// countByType tallies the snapshots per type for assertions.
func countByType(snapshots []QuestionSnapshot) map[questions.QuestionType]int {
	counts := map[questions.QuestionType]int{}
	for _, snapshot := range snapshots {
		counts[snapshot.Type]++
	}
	return counts
}

// ─── 组卷逻辑 ────────────────────────────────────────────────────────

// 各题型选题数量与 strategy 一致：单选 2 题、判断 1 题，多选/填空为 0
// 不选题；选出的题目均来自注入的题目源且快照字段完整。
func TestGeneratePicksExpectedCountsPerType(t *testing.T) {
	store := NewInMemoryStore()
	source := &fakeSource{byType: map[questions.QuestionType][]questions.Question{
		questions.QuestionTypeSingle:   bankQuestions(3, questions.QuestionTypeSingle),
		questions.QuestionTypeJudgment: bankQuestions(2, questions.QuestionTypeJudgment),
	}}
	service := NewService(store, source)
	paper := mustCreate(t, service, paperInput("组卷试卷", 60, 60, map[string]any{
		"单选": 2, "多选": 0, "判断": 1, "填空": 0,
	}))

	generated, err := service.Generate(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	counts := countByType(generated.Questions)
	if counts[questions.QuestionTypeSingle] != 2 {
		t.Fatalf("单选 count = %d, want 2", counts[questions.QuestionTypeSingle])
	}
	if counts[questions.QuestionTypeJudgment] != 1 {
		t.Fatalf("判断 count = %d, want 1", counts[questions.QuestionTypeJudgment])
	}
	if counts[questions.QuestionTypeMultiple] != 0 || counts[questions.QuestionTypeFill] != 0 {
		t.Fatalf("unexpected picks: %v", counts)
	}
	// 每个快照都来自注入的题目源且字段完整。
	ids := map[string]bool{}
	for _, question := range source.byType[questions.QuestionTypeSingle] {
		ids[question.ID] = true
	}
	for _, question := range source.byType[questions.QuestionTypeJudgment] {
		ids[question.ID] = true
	}
	for _, snapshot := range generated.Questions {
		if !ids[snapshot.ID] {
			t.Fatalf("snapshot %+v does not come from the injected source", snapshot)
		}
		if snapshot.ID == "" || snapshot.Content == "" || snapshot.Difficulty < 1 {
			t.Fatalf("snapshot %+v has incomplete fields", snapshot)
		}
	}
	// generate 后 GET 反映新的 questions。
	fetched, err := service.Get(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("get after generate: %v", err)
	}
	if len(fetched.Questions) != 3 {
		t.Fatalf("get after generate: questions = %d, want 3", len(fetched.Questions))
	}
}

// 某题型题库不足时返回 GenerationError，缺口说明含缺题目类型与数量；
// 组卷失败时试卷原样保留。
func TestGenerateInsufficientBankReportsGaps(t *testing.T) {
	store := NewInMemoryStore()
	source := &fakeSource{byType: map[questions.QuestionType][]questions.Question{
		questions.QuestionTypeSingle: bankQuestions(1, questions.QuestionTypeSingle),
		// 多选/判断/填空题库为空。
	}}
	service := NewService(store, source)
	paper := mustCreate(t, service, paperInput("组卷试卷", 60, 60, map[string]any{
		"单选": 2, "多选": 1, "判断": 3,
	}))

	_, err := service.Generate(context.Background(), paper.ID)
	var generationError *GenerationError
	if !errors.As(err, &generationError) {
		t.Fatalf("generate: err = %v, want *GenerationError", err)
	}
	if !strings.Contains(generationError.Message, "题库不足") {
		t.Fatalf("error %q does not mention 题库不足", generationError.Message)
	}
	for _, gap := range []string{"单选缺 1 题", "多选缺 1 题", "判断缺 3 题"} {
		if !strings.Contains(generationError.Message, gap) {
			t.Fatalf("error %q does not contain %q", generationError.Message, gap)
		}
	}
	// 组卷失败不写试卷：questions 仍为空。
	fetched, err := service.Get(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("get after failed generate: %v", err)
	}
	if len(fetched.Questions) != 0 {
		t.Fatalf("failed generate must not touch questions, got %d", len(fetched.Questions))
	}
}

// 题库恰好够数的边界：每题型的题库数量恰好等于 strategy 要求时组卷成功，
// 题目全部来自题库。
func TestGenerateExactFitBoundary(t *testing.T) {
	store := NewInMemoryStore()
	source := &fakeSource{byType: map[questions.QuestionType][]questions.Question{
		questions.QuestionTypeSingle: bankQuestions(2, questions.QuestionTypeSingle),
		questions.QuestionTypeFill:   bankQuestions(1, questions.QuestionTypeFill),
	}}
	service := NewService(store, source)
	paper := mustCreate(t, service, paperInput("组卷试卷", 60, 60, map[string]any{
		"单选": 2, "填空": 1,
	}))

	generated, err := service.Generate(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	counts := countByType(generated.Questions)
	if counts[questions.QuestionTypeSingle] != 2 || counts[questions.QuestionTypeFill] != 1 {
		t.Fatalf("counts = %v, want 单选 2 / 填空 1", counts)
	}
	// 恰好够数时全部题目都被选入。
	seen := map[string]bool{}
	for _, snapshot := range generated.Questions {
		seen[snapshot.ID] = true
	}
	for _, question := range append(source.byType[questions.QuestionTypeSingle], source.byType[questions.QuestionTypeFill]...) {
		if !seen[question.ID] {
			t.Fatalf("question %s not picked though the bank exactly fits", question.ID)
		}
	}
}

// 空题库：任何题型都需要题目时组卷失败并给出缺口。
func TestGenerateEmptyBank(t *testing.T) {
	store := NewInMemoryStore()
	service := NewService(store, &fakeSource{byType: map[questions.QuestionType][]questions.Question{}})
	paper := mustCreate(t, service, paperInput("组卷试卷", 60, 60, map[string]any{"单选": 1}))

	_, err := service.Generate(context.Background(), paper.ID)
	var generationError *GenerationError
	if !errors.As(err, &generationError) {
		t.Fatalf("generate: err = %v, want *GenerationError", err)
	}
	if !strings.Contains(generationError.Message, "单选缺 1 题") {
		t.Fatalf("error %q does not contain the gap 单选缺 1 题", generationError.Message)
	}
}

// 覆盖语义：更新 strategy 后重复组卷覆盖上次结果（旧题目全部被替换）。
func TestGenerateOverwritesPreviousResult(t *testing.T) {
	store := NewInMemoryStore()
	source := &fakeSource{byType: map[questions.QuestionType][]questions.Question{
		questions.QuestionTypeSingle:   bankQuestions(3, questions.QuestionTypeSingle),
		questions.QuestionTypeJudgment: bankQuestions(2, questions.QuestionTypeJudgment),
	}}
	service := NewService(store, source)
	paper := mustCreate(t, service, paperInput("组卷试卷", 60, 60, map[string]any{"单选": 2}))

	generated, err := service.Generate(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("first generate: %v", err)
	}
	if len(generated.Questions) != 2 || countByType(generated.Questions)[questions.QuestionTypeSingle] != 2 {
		t.Fatalf("first generate questions = %v, want 2 单选", generated.Questions)
	}

	// 更新 strategy 后再次组卷：结果覆盖上次的 2 道单选。
	updated, err := service.Update(context.Background(), paper.ID, paperInput("组卷试卷", 60, 60, map[string]any{"判断": 1}))
	if err != nil {
		t.Fatalf("update: %v", err)
	}
	// Update 不触碰 questions：上次组卷结果保留。
	if len(updated.Questions) != 2 {
		t.Fatalf("update must not touch questions, got %d", len(updated.Questions))
	}

	regenerated, err := service.Generate(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("second generate: %v", err)
	}
	counts := countByType(regenerated.Questions)
	if counts[questions.QuestionTypeJudgment] != 1 || counts[questions.QuestionTypeSingle] != 0 {
		t.Fatalf("second generate questions = %v, want exactly 1 判断 (old 单选 replaced)", counts)
	}

	fetched, err := service.Get(context.Background(), paper.ID)
	if err != nil {
		t.Fatalf("get after second generate: %v", err)
	}
	if len(fetched.Questions) != 1 || fetched.Questions[0].Type != questions.QuestionTypeJudgment {
		t.Fatalf("get after second generate: questions = %v, want the replaced 判断 question", fetched.Questions)
	}
}

// 对不存在的试卷组卷返回 ErrNotFound。
func TestGenerateUnknownPaper(t *testing.T) {
	service := NewService(NewInMemoryStore(), &fakeSource{byType: map[questions.QuestionType][]questions.Question{}})
	if _, err := service.Generate(context.Background(), "01ARZ3NDEKTSV4RRFFQ69G5FAV"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("generate unknown paper: err = %v, want ErrNotFound", err)
	}
}

// ─── strategy 校验 ───────────────────────────────────────────────────

// 非法 generation_strategy（未知题型键/负数/非整数/全零/缺省）→ ValidationError。
func TestCreateRejectsInvalidStrategy(t *testing.T) {
	service := NewService(NewInMemoryStore(), &fakeSource{byType: map[questions.QuestionType][]questions.Question{}})
	for name, strategy := range map[string]map[string]any{
		"missing":          nil,
		"unknown key":      {"选择题": 1},
		"negative":         {"单选": -1},
		"non-integer":      {"单选": 1.5},
		"non-number value": {"单选": "2"},
		"all zero":         {"单选": 0, "多选": 0},
		"empty object":     {},
	} {
		_, err := service.Create(context.Background(), paperInput("试卷", 60, 60, strategy))
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want *ValidationError", name, err)
		}
	}
}

// pass_score 0 合法、缺省必报错；duration_minutes 与 pass_score 越界报错。
func TestCreateRejectsInvalidDurationAndPassScore(t *testing.T) {
	service := NewService(NewInMemoryStore(), &fakeSource{byType: map[questions.QuestionType][]questions.Question{}})
	valid := map[string]any{"单选": 1}

	zeroPass := paperInput("试卷", 60, 0, valid)
	if _, err := service.Create(context.Background(), zeroPass); err != nil {
		t.Fatalf("pass_score 0 must be legal, got %v", err)
	}

	cases := []struct {
		name  string
		input Input
	}{
		{"missing duration", Input{Title: "试卷", PassScore: intPtr(60), GenerationStrategy: valid}},
		{"zero duration", paperInput("试卷", 0, 60, valid)},
		{"negative duration", paperInput("试卷", -5, 60, valid)},
		{"missing pass_score", Input{Title: "试卷", DurationMinutes: intPtr(60), GenerationStrategy: valid}},
		{"negative pass_score", paperInput("试卷", 60, -1, valid)},
		{"pass_score above 100", paperInput("试卷", 60, 101, valid)},
		{"missing title", Input{DurationMinutes: intPtr(60), PassScore: intPtr(60), GenerationStrategy: valid}},
		{"blank title", paperInput("  ", 60, 60, valid)},
	}
	for _, testCase := range cases {
		_, err := service.Create(context.Background(), testCase.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want *ValidationError", testCase.name, err)
		}
	}
}
