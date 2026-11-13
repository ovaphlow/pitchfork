package positions

import (
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
)

func intPointer(value int) *int { return &value }

func TestStatusTransitions(t *testing.T) {
	tests := []struct {
		from Status
		to   Status
		want bool
	}{
		{StatusDraft, StatusOpen, true},
		{StatusDraft, StatusClosed, false},
		{StatusOpen, StatusPaused, true},
		{StatusOpen, StatusClosed, true},
		{StatusOpen, StatusDraft, false},
		{StatusPaused, StatusOpen, true},
		{StatusPaused, StatusClosed, true},
		{StatusClosed, StatusOpen, false},
		{StatusClosed, StatusDraft, false},
	}
	for _, test := range tests {
		if got := test.from.CanTransitionTo(test.to); got != test.want {
			t.Errorf("%s -> %s = %v, want %v", test.from, test.to, got, test.want)
		}
	}
}

func TestStatusInputValidate(t *testing.T) {
	next, err := StatusInput{Status: StatusOpen}.Validate(StatusDraft)
	if err != nil || next != StatusOpen {
		t.Fatalf("Validate = %q, %v", next, err)
	}
	if _, err := (StatusInput{Status: StatusOpen}).Validate(StatusClosed); err == nil {
		t.Fatal("a closed posting cannot reopen")
	}
	if _, err := (StatusInput{Status: "已过期"}).Validate(StatusOpen); err == nil {
		t.Fatal("unknown states must be rejected")
	}
}

func TestCreateInputValidate(t *testing.T) {
	priced := func() CreateInput {
		return CreateInput{
			OrganizationID: "01ARZ3NDEKTSV4RRFFQ69G5FAV",
			Title:          "  护士  ",
			SalaryMin:      intPointer(9000),
			SalaryMax:      intPointer(12000),
		}
	}

	tests := []struct {
		name    string
		mutate  func(*CreateInput)
		wantErr string
	}{
		{
			name:    "organisation is required",
			mutate:  func(in *CreateInput) { in.OrganizationID = " " },
			wantErr: "必须指定所属机构",
		},
		{
			name:    "title is required",
			mutate:  func(in *CreateInput) { in.Title = "" },
			wantErr: "岗位名称不能为空",
		},
		{
			name:    "salary range is required when not negotiable",
			mutate:  func(in *CreateInput) { in.SalaryMin = nil },
			wantErr: "非面议岗位必须填写薪资下限",
		},
		{
			name:    "upper bound must cover the lower bound",
			mutate:  func(in *CreateInput) { in.SalaryMax = intPointer(5000) },
			wantErr: "薪资上限不能低于下限",
		},
		{
			name: "negotiable must not carry a range",
			mutate: func(in *CreateInput) {
				in.SalaryNegotiable = true
			},
			wantErr: "面议岗位不能填写薪资区间",
		},
		{
			name:    "employment type must be known",
			mutate:  func(in *CreateInput) { in.EmploymentType = "外包" },
			wantErr: "用工类型无效",
		},
		{
			name:    "headcount is bounded",
			mutate:  func(in *CreateInput) { in.Headcount = 5000 },
			wantErr: "招聘人数必须在 1 到 999 之间",
		},
		{
			name:    "apply method must be known",
			mutate:  func(in *CreateInput) { in.Metadata.Apply = Apply{Method: "fax", Value: "1"} },
			wantErr: "投递方式无效",
		},
		{
			name: "apply block must be complete",
			mutate: func(in *CreateInput) {
				in.Metadata.Apply = Apply{Method: "email"}
			},
			wantErr: "投递方式与投递目标必须同时填写",
		},
		{
			name:   "defaults are applied",
			mutate: func(in *CreateInput) {},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			input := priced()
			test.mutate(&input)

			normalised, err := input.Validate()
			if test.wantErr == "" {
				if err != nil {
					t.Fatalf("Validate: %v", err)
				}
				if normalised.Title != "护士" {
					t.Fatalf("title = %q, want it trimmed", normalised.Title)
				}
				if normalised.EmploymentType != EmploymentTypes[0] || normalised.Education != Educations[0] {
					t.Fatalf("defaults not applied: %+v", normalised)
				}
				return
			}

			message, ok := apierr.ValidationMessage(err)
			if !ok || message != test.wantErr {
				t.Fatalf("error = %v (%q), want %q", err, message, test.wantErr)
			}
		})
	}
}

func TestCancellableSalaryDefaults(t *testing.T) {
	input, err := CreateInput{
		OrganizationID:   "01ARZ3NDEKTSV4RRFFQ69G5FAV",
		Title:            "主治医师",
		SalaryNegotiable: true,
	}.Validate()
	if err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if input.SalaryMin != nil || input.SalaryMax != nil {
		t.Fatal("negociable postings must not store a range")
	}
	if input.Headcount != 1 {
		t.Fatalf("headcount = %d, want 1", input.Headcount)
	}
}

func TestUpdateInputValidateRejectsBadVocabulary(t *testing.T) {
	experience := strings.Repeat("x", maxRegionRunes+1)
	if _, err := (UpdateInput{Experience: &experience}).Validate(); err == nil {
		t.Fatal("oversized values must be rejected")
	}

	education := "小学"
	if _, err := (UpdateInput{Education: &education}).Validate(); err == nil {
		t.Fatal("unknown education must be rejected")
	}
}

func TestPositionPublicHonoursExpiry(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	past := now.Add(-time.Hour)
	future := now.Add(time.Hour)

	tests := []struct {
		name      string
		status    Status
		expiresAt *time.Time
		want      bool
	}{
		{"open without expiry", StatusOpen, nil, true},
		{"open with future expiry", StatusOpen, &future, true},
		{"open with past expiry", StatusOpen, &past, false},
		{"draft", StatusDraft, nil, false},
		{"paused", StatusPaused, nil, false},
		{"closed", StatusClosed, nil, false},
	}
	for _, test := range tests {
		position := Position{Status: test.status, ExpiresAt: test.expiresAt}
		if got := position.Public(now); got != test.want {
			t.Errorf("%s: Public = %v, want %v", test.name, got, test.want)
		}
	}
}

func TestMetadataValidateDeduplicatesTags(t *testing.T) {
	metadata, err := Metadata{Tags: []string{"急招", "急招", " 双休 "}}.Validate()
	if err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if len(metadata.Tags) != 2 || metadata.Tags[1] != "双休" {
		t.Fatalf("tags = %v", metadata.Tags)
	}
}
