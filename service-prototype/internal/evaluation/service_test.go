package evaluation

import (
	"context"
	"errors"
	"testing"
	"time"
)

// fixtureScoreRefs is the test fixture behind the ScoreRefChecker
// interface: it returns a fixed reference count for every indicator
// (the future evaluation_scores store implements the same interface).
type fixtureScoreRefs struct{ count int }

func (f *fixtureScoreRefs) CountScoresByIndicator(context.Context, string) (int, error) {
	return f.count, nil
}

// TestCreateIndicatorDefaults: a minimal create applies the domain
// defaults — weight 1, demo false, sort_order 0, description ” and
// created_by ” — and returns a server-generated 26-character Crockford
// Base32 ULID with server timestamps.
func TestCreateIndicatorDefaults(t *testing.T) {
	service := NewService(NewInMemoryStore())
	indicator, err := service.CreateIndicator(context.Background(), IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "  响应速度测试指标  ",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}
	if !ulidPattern.MatchString(indicator.ID) {
		t.Errorf("id = %q, want a 26-character Crockford Base32 ULID", indicator.ID)
	}
	if indicator.Title != "响应速度测试指标" {
		t.Errorf("title = %q, want the trimmed value", indicator.Title)
	}
	if indicator.Weight != 1 {
		t.Errorf("weight = %d, want default 1", indicator.Weight)
	}
	if indicator.Demo {
		t.Errorf("demo = true, want default false")
	}
	if indicator.SortOrder != 0 {
		t.Errorf("sort_order = %d, want default 0", indicator.SortOrder)
	}
	if indicator.Description != "" || indicator.CreatedBy != "" {
		t.Errorf("description/created_by = %q/%q, want empty defaults", indicator.Description, indicator.CreatedBy)
	}
	if indicator.CreatedAt.IsZero() || !indicator.CreatedAt.Equal(indicator.UpdatedAt) {
		t.Errorf("timestamps = %v/%v, want set and equal", indicator.CreatedAt, indicator.UpdatedAt)
	}
}

// TestCreateIndicatorCarriesExplicitFields: explicit weight/demo/
// sort_order/description/created_by values are stored and echoed.
func TestCreateIndicatorCarriesExplicitFields(t *testing.T) {
	service := NewService(NewInMemoryStore())
	weight, sortOrder := 3, 2
	demo := true
	indicator, err := service.CreateIndicator(context.Background(), IndicatorInput{
		Dimension:   DimensionAudienceSafety,
		Title:       "观众疏散组织",
		Weight:      &weight,
		Demo:        &demo,
		SortOrder:   &sortOrder,
		Description: "疏散组织有序、路线合理",
		CreatedBy:   "admin",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}
	if indicator.Weight != 3 || !indicator.Demo || indicator.SortOrder != 2 {
		t.Errorf("weight/demo/sort_order = %d/%v/%d, want 3/true/2", indicator.Weight, indicator.Demo, indicator.SortOrder)
	}
	if indicator.Description != "疏散组织有序、路线合理" || indicator.CreatedBy != "admin" {
		t.Errorf("description/created_by = %q/%q, want the explicit values", indicator.Description, indicator.CreatedBy)
	}
}

// TestCreateIndicatorValidation: create rejects a missing title, an
// invalid dimension, a weight below 1 and a negative sort_order with a
// ValidationError (mapped to HTTP 400).
func TestCreateIndicatorValidation(t *testing.T) {
	service := NewService(NewInMemoryStore())
	ctx := context.Background()
	zero, negative := 0, -1

	cases := []struct {
		name  string
		input IndicatorInput
	}{
		{"missing title", IndicatorInput{Dimension: DimensionResponseSpeed, Title: ""}},
		{"blank title", IndicatorInput{Dimension: DimensionResponseSpeed, Title: "   "}},
		{"invalid dimension", IndicatorInput{Dimension: "其他", Title: "测试"}},
		{"zero weight", IndicatorInput{Dimension: DimensionResponseSpeed, Title: "测试", Weight: &zero}},
		{"negative weight", IndicatorInput{Dimension: DimensionResponseSpeed, Title: "测试", Weight: &negative}},
		{"negative sort_order", IndicatorInput{Dimension: DimensionResponseSpeed, Title: "测试", SortOrder: &negative}},
	}
	for _, tc := range cases {
		_, err := service.CreateIndicator(ctx, tc.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Errorf("%s: err = %v, want a ValidationError", tc.name, err)
		}
	}
}

// TestUpdateIndicator: PUT semantics — the fields of the request are
// applied, omitted fields are reset to their defaults (weight 1, demo
// false, sort_order 0, description ”, created_by ”), created_at is
// preserved and updated_at is refreshed.
func TestUpdateIndicator(t *testing.T) {
	service := NewService(NewInMemoryStore())
	ctx := context.Background()
	weight, sortOrder := 3, 2
	demo := true
	created, err := service.CreateIndicator(ctx, IndicatorInput{
		Dimension:   DimensionAudienceSafety,
		Title:       "观众疏散组织",
		Weight:      &weight,
		Demo:        &demo,
		SortOrder:   &sortOrder,
		Description: "疏散组织有序、路线合理",
		CreatedBy:   "admin",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}

	// Sleep briefly so the refreshed updated_at is observably later.
	time.Sleep(2 * time.Millisecond)
	updated, err := service.UpdateIndicator(ctx, created.ID, IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "预警响应速度",
	})
	if err != nil {
		t.Fatalf("UpdateIndicator: %v", err)
	}
	if updated.ID != created.ID {
		t.Errorf("id = %q, want %q (preserved)", updated.ID, created.ID)
	}
	if updated.Dimension != DimensionResponseSpeed || updated.Title != "预警响应速度" {
		t.Errorf("dimension/title = %q/%q, want the new values", updated.Dimension, updated.Title)
	}
	if updated.Weight != 1 || updated.Demo || updated.SortOrder != 0 {
		t.Errorf("weight/demo/sort_order = %d/%v/%d, want the defaults 1/false/0 (omitted fields reset)", updated.Weight, updated.Demo, updated.SortOrder)
	}
	if updated.Description != "" || updated.CreatedBy != "" {
		t.Errorf("description/created_by = %q/%q, want empty (omitted fields reset)", updated.Description, updated.CreatedBy)
	}
	if !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Errorf("created_at = %v, want %v (preserved)", updated.CreatedAt, created.CreatedAt)
	}
	if !updated.UpdatedAt.After(created.UpdatedAt) {
		t.Errorf("updated_at = %v, want later than %v (refreshed)", updated.UpdatedAt, created.UpdatedAt)
	}

	// The GET path reflects the update.
	got, err := service.GetIndicator(ctx, created.ID)
	if err != nil {
		t.Fatalf("GetIndicator: %v", err)
	}
	if got.Title != "预警响应速度" || got.Dimension != DimensionResponseSpeed {
		t.Errorf("GET after PUT = %q/%q, want the updated values", got.Title, got.Dimension)
	}
}

// TestUpdateIndicatorValidation: update rejects the same invalid inputs
// as create (missing title, invalid dimension, weight below 1) and a
// missing id is a 404.
func TestUpdateIndicatorValidation(t *testing.T) {
	service := NewService(NewInMemoryStore())
	ctx := context.Background()
	zero := 0
	created, err := service.CreateIndicator(ctx, IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "预警响应速度",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}

	cases := []struct {
		name  string
		id    string
		input IndicatorInput
	}{
		{"missing title", created.ID, IndicatorInput{Dimension: DimensionResponseSpeed, Title: ""}},
		{"invalid dimension", created.ID, IndicatorInput{Dimension: "其他", Title: "测试"}},
		{"zero weight", created.ID, IndicatorInput{Dimension: DimensionResponseSpeed, Title: "测试", Weight: &zero}},
		{"unknown id", "06G01KFRJTE84EP3234FBD2YD4", IndicatorInput{Dimension: DimensionResponseSpeed, Title: "测试"}},
	}
	for _, tc := range cases {
		_, err := service.UpdateIndicator(ctx, tc.id, tc.input)
		if tc.name == "unknown id" {
			if !errors.Is(err, ErrIndicatorNotFound) {
				t.Errorf("%s: err = %v, want ErrIndicatorNotFound", tc.name, err)
			}
			continue
		}
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Errorf("%s: err = %v, want a ValidationError", tc.name, err)
		}
	}
}

// TestDeleteIndicatorReferenced: deleting an indicator whose score
// reference count is positive is rejected with ErrIndicatorReferenced
// (message 指标已被评分引用，请先清理评分) and the indicator stays in
// place; a zero count deletes it.
func TestDeleteIndicatorReferenced(t *testing.T) {
	service := NewService(NewInMemoryStore())
	ctx := context.Background()
	created, err := service.CreateIndicator(ctx, IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "预警响应速度",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}

	service.SetScoreRefChecker(&fixtureScoreRefs{count: 2})
	if err := service.DeleteIndicator(ctx, created.ID); err == nil {
		t.Fatal("DeleteIndicator with references: err = nil, want ErrIndicatorReferenced")
	} else if !errors.Is(err, ErrIndicatorReferenced) {
		t.Fatalf("DeleteIndicator with references: err = %v, want ErrIndicatorReferenced", err)
	} else if err.Error() != "指标已被评分引用，请先清理评分" {
		t.Errorf("error message = %q, want 指标已被评分引用，请先清理评分", err.Error())
	}
	if _, err := service.GetIndicator(ctx, created.ID); err != nil {
		t.Errorf("indicator after rejected delete: GetIndicator: %v (must still exist)", err)
	}

	// A zero reference count allows the deletion.
	service.SetScoreRefChecker(&fixtureScoreRefs{count: 0})
	if err := service.DeleteIndicator(ctx, created.ID); err != nil {
		t.Fatalf("DeleteIndicator without references: %v", err)
	}
	if _, err := service.GetIndicator(ctx, created.ID); !errors.Is(err, ErrIndicatorNotFound) {
		t.Errorf("indicator after delete: err = %v, want ErrIndicatorNotFound", err)
	}
}

// TestDeleteIndicatorWithoutChecker: without a wired score-ref checker
// the deletion succeeds (the reference rule is opt-in until the
// evaluation_scores store lands).
func TestDeleteIndicatorWithoutChecker(t *testing.T) {
	service := NewService(NewInMemoryStore())
	ctx := context.Background()
	created, err := service.CreateIndicator(ctx, IndicatorInput{
		Dimension: DimensionResponseSpeed,
		Title:     "预警响应速度",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}
	if err := service.DeleteIndicator(ctx, created.ID); err != nil {
		t.Fatalf("DeleteIndicator: %v", err)
	}
	if _, err := service.GetIndicator(ctx, created.ID); !errors.Is(err, ErrIndicatorNotFound) {
		t.Errorf("indicator after delete: err = %v, want ErrIndicatorNotFound", err)
	}
}

// TestDeleteIndicatorNotFound: deleting a missing id is a 404 and the
// reference source is never consulted.
func TestDeleteIndicatorNotFound(t *testing.T) {
	service := NewService(NewInMemoryStore())
	if err := service.DeleteIndicator(context.Background(), "06G01KFRJTE84EP3234FBD2YD4"); !errors.Is(err, ErrIndicatorNotFound) {
		t.Fatalf("err = %v, want ErrIndicatorNotFound", err)
	}
}

// TestListIndicatorsEmpty: the empty store returns zero records and a
// zero total.
func TestListIndicatorsEmpty(t *testing.T) {
	service := NewService(NewInMemoryStore())
	records, total, err := service.ListIndicators(context.Background(), IndicatorFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListIndicators: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("total/records = %d/%d, want 0/0", total, len(records))
	}
}

// TestListIndicatorsFilterPaginationSort: the dimension filter narrows
// the match, limit/offset paginate and the sort order is (dimension,
// sort_order, created_at) ascending. The dimension byte order (UTF-8,
// i.e. codepoint order) is 协同效率 < 响应速度 < 处置规范性 < 文物安全 <
// 舆情管控 < 观众安全, so a row with a lower sort_order but a later
// dimension never overtakes a dimension group.
func TestListIndicatorsFilterPaginationSort(t *testing.T) {
	service := NewService(NewInMemoryStore())
	ctx := context.Background()

	// One row per dimension plus a second 响应速度 row with a smaller
	// sort_order than the first (created later, so created_at cannot
	// reorder the (dimension, sort_order) groups).
	rows := []IndicatorInput{
		{Dimension: DimensionAudienceSafety, Title: "观众疏散组织", SortOrder: intPtr(1)},
		{Dimension: DimensionResponseSpeed, Title: "力量到场速度", SortOrder: intPtr(3)},
		{Dimension: DimensionCoordination, Title: "部门协同效率", SortOrder: intPtr(1)},
		{Dimension: DimensionRelicSafety, Title: "文物转移保护", SortOrder: intPtr(1)},
		{Dimension: DimensionPublicOpinion, Title: "舆情监测预警", SortOrder: intPtr(1)},
		{Dimension: DimensionDisposalStandard, Title: "处置流程规范性", SortOrder: intPtr(1)},
		{Dimension: DimensionResponseSpeed, Title: "预警响应速度", SortOrder: intPtr(1)},
	}
	for _, input := range rows {
		if _, err := service.CreateIndicator(ctx, input); err != nil {
			t.Fatalf("CreateIndicator(%s): %v", input.Title, err)
		}
	}

	records, total, err := service.ListIndicators(ctx, IndicatorFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListIndicators: %v", err)
	}
	if total != 7 {
		t.Fatalf("total = %d, want 7", total)
	}
	var titles []string
	for _, indicator := range records {
		titles = append(titles, indicator.Title)
	}
	want := []string{
		"部门协同效率",  // 协同效率 (sort 1)
		"预警响应速度",  // 响应速度 (sort 1)
		"力量到场速度",  // 响应速度 (sort 3)
		"处置流程规范性", // 处置规范性 (sort 1)
		"文物转移保护",  // 文物安全 (sort 1)
		"舆情监测预警",  // 舆情管控 (sort 1)
		"观众疏散组织",  // 观众安全 (sort 1)
	}
	if len(titles) != len(want) {
		t.Fatalf("record titles = %v, want %v", titles, want)
	}
	for index := range want {
		if titles[index] != want[index] {
			t.Fatalf("sort order diverges at %d: got %v, want %v", index, titles, want)
		}
	}

	// Dimension filter narrows to the 响应速度 rows, still sorted.
	filtered, filteredTotal, err := service.ListIndicators(ctx, IndicatorFilter{Dimension: DimensionResponseSpeed, Limit: 50})
	if err != nil {
		t.Fatalf("ListIndicators(filter): %v", err)
	}
	if filteredTotal != 2 || len(filtered) != 2 {
		t.Fatalf("filtered total/records = %d/%d, want 2/2", filteredTotal, len(filtered))
	}
	if filtered[0].Title != "预警响应速度" || filtered[1].Title != "力量到场速度" {
		t.Errorf("filtered titles = %q/%q, want 预警响应速度/力量到场速度", filtered[0].Title, filtered[1].Title)
	}

	// Pagination: limit 3 offset 2 returns the third to fifth rows and
	// keeps the total at 7.
	page, pageTotal, err := service.ListIndicators(ctx, IndicatorFilter{Limit: 3, Offset: 2})
	if err != nil {
		t.Fatalf("ListIndicators(page): %v", err)
	}
	if pageTotal != 7 || len(page) != 3 {
		t.Fatalf("page total/records = %d/%d, want 7/3", pageTotal, len(page))
	}
	if page[0].Title != "力量到场速度" || page[2].Title != "文物转移保护" {
		t.Errorf("page titles = %q..%q, want 力量到场速度..文物转移保护", page[0].Title, page[2].Title)
	}
}
