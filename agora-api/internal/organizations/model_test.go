package organizations

import (
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
)

func TestCreateInputValidate(t *testing.T) {
	tests := []struct {
		name    string
		input   CreateInput
		wantErr string
	}{
		{
			name:    "name is required",
			input:   CreateInput{},
			wantErr: "机构名称不能为空",
		},
		{
			name:    "name is bounded",
			input:   CreateInput{Name: strings.Repeat("机", maxNameRunes+1)},
			wantErr: "机构名称不能超过 120 个字符",
		},
		{
			name:    "org type must be known",
			input:   CreateInput{Name: "甲医院", OrgType: "NGO"},
			wantErr: "机构类型无效",
		},
		{
			name:    "website must be absolute",
			input:   CreateInput{Name: "甲医院", Metadata: Metadata{Website: "example.com"}},
			wantErr: "网站必须以 http:// 或 https:// 开头",
		},
		{
			name:    "founded year must be plausible",
			input:   CreateInput{Name: "甲医院", Metadata: Metadata{FoundedYear: 1600}},
			wantErr: "成立年份超出合理范围",
		},
		{
			name:    "email must look like an address",
			input:   CreateInput{Name: "甲医院", Metadata: Metadata{Contact: Contact{Email: "nope"}}},
			wantErr: "联系邮箱格式无效",
		},
		{
			name:    "license file id is bounded",
			input:   CreateInput{Name: "甲医院", Metadata: Metadata{LicenseFileID: strings.Repeat("A", maxLogoFileID+1)}},
			wantErr: "营业执照不能超过 64 个字符",
		},
		{
			name: "defaults are applied",
			input: CreateInput{Name: "  甲医院  ", City: " 上海 ", Metadata: Metadata{
				Tags: []string{" 双休 ", "双休", "五险一金"},
			}},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			normalised, err := test.input.Validate()
			if test.wantErr == "" {
				if err != nil {
					t.Fatalf("Validate: %v", err)
				}
				if normalised.Name != "甲医院" || normalised.City != "上海" {
					t.Fatalf("values were not trimmed: %+v", normalised)
				}
				if normalised.OrgType != OrgTypes[0] {
					t.Fatalf("org type = %q, want default %q", normalised.OrgType, OrgTypes[0])
				}
				if len(normalised.Metadata.Tags) != 2 {
					t.Fatalf("tags = %v, want duplicates removed", normalised.Metadata.Tags)
				}
				return
			}

			if err == nil {
				t.Fatalf("expected error %q", test.wantErr)
			}
			if message, ok := apierr.ValidationMessage(err); !ok || message != test.wantErr {
				t.Fatalf("error = %v (%q), want %q", err, message, test.wantErr)
			}
		})
	}
}

func TestCreateInputKeepsReviewEvidence(t *testing.T) {
	input, err := (CreateInput{
		Name: "甲医院",
		Metadata: Metadata{
			CreditCode:    " 91310000MA1K35Q25X ",
			LicenseFileID: " 01ARZ3NDEKTSV4RRFFQ69G5FAV ",
		},
	}).Validate()
	if err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if input.Metadata.CreditCode != "91310000MA1K35Q25X" {
		t.Fatalf("credit code = %q", input.Metadata.CreditCode)
	}
	if input.Metadata.LicenseFileID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" {
		t.Fatalf("license file id = %q", input.Metadata.LicenseFileID)
	}
}

func TestReviewInputValidate(t *testing.T) {
	if _, err := (ReviewInput{Status: StatusVerified}).Validate(); err != nil {
		t.Fatalf("verifying without a reason must be allowed: %v", err)
	}
	if _, err := (ReviewInput{Status: StatusRejected}).Validate(); err == nil {
		t.Fatal("rejecting without a reason must fail")
	}
	if _, err := (ReviewInput{Status: StatusPending}).Validate(); err == nil {
		t.Fatal("returning to 待审核 through review must fail")
	}
}

func TestRoleCapabilities(t *testing.T) {
	tests := []struct {
		role           Role
		editProfile    bool
		manageMembers  bool
		managePosition bool
	}{
		{RoleOwner, true, true, true},
		{RoleAdmin, true, true, true},
		{RoleRecruiter, false, false, true},
	}
	for _, test := range tests {
		if got := test.role.CanEditProfile(); got != test.editProfile {
			t.Errorf("%s CanEditProfile = %v", test.role, got)
		}
		if got := test.role.CanManageMembers(); got != test.manageMembers {
			t.Errorf("%s CanManageMembers = %v", test.role, got)
		}
		if got := test.role.CanManagePositions(); got != test.managePosition {
			t.Errorf("%s CanManagePositions = %v", test.role, got)
		}
	}
}

func TestValidateMemberRole(t *testing.T) {
	if _, err := ValidateMemberRole(RoleAdmin); err != nil {
		t.Fatalf("ValidateMemberRole: %v", err)
	}
	if _, err := ValidateMemberRole("老板"); err == nil {
		t.Fatal("unknown roles must be rejected")
	}
}

func TestUpdateInputValidateLeavesUnsetFieldsAlone(t *testing.T) {
	name := " 乙学校 "
	input, err := UpdateInput{Name: &name}.Validate()
	if err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if input.Name == nil || *input.Name != "乙学校" {
		t.Fatalf("name = %v", input.Name)
	}
	if input.City != nil || input.Metadata != nil {
		t.Fatal("unset fields must stay nil")
	}
}
