// Package organizations owns the institution directory: the subjects trading
// under an organisation, their members, and the review state that decides
// whether the organisation may publish job positions.
package organizations

import (
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
)

// Status is the review state of an organisation.
type Status string

// Review states. Only StatusVerified organisations may publish positions.
const (
	StatusPending  Status = "待审核"
	StatusVerified Status = "已认证"
	StatusRejected Status = "已驳回"
	StatusDisabled Status = "已停用"
)

// Statuses lists every accepted review state.
var Statuses = []Status{StatusPending, StatusVerified, StatusRejected, StatusDisabled}

// Valid reports whether s is a known review state.
func (s Status) Valid() bool {
	for _, candidate := range Statuses {
		if candidate == s {
			return true
		}
	}
	return false
}

// OrgTypes are the accepted organisation categories.
var OrgTypes = []string{"企业", "医院", "学校", "社会组织", "政府机关", "其他"}

// Role is a member's authority inside one organisation.
type Role string

// Member roles, in descending authority.
const (
	RoleOwner     Role = "所有者"
	RoleAdmin     Role = "管理员"
	RoleRecruiter Role = "招聘专员"
)

// Roles lists every accepted member role.
var Roles = []Role{RoleOwner, RoleAdmin, RoleRecruiter}

// CanEditProfile reports whether the role may change the organisation record.
func (r Role) CanEditProfile() bool {
	return r == RoleOwner || r == RoleAdmin
}

// CanManageMembers reports whether the role may add or remove members.
func (r Role) CanManageMembers() bool {
	return r == RoleOwner || r == RoleAdmin
}

// CanManagePositions reports whether the role may maintain job positions.
func (r Role) CanManagePositions() bool {
	return r == RoleOwner || r == RoleAdmin || r == RoleRecruiter
}

// Contact is the public contact block stored in metadata.
type Contact struct {
	Name  string `json:"name,omitempty"`
	Phone string `json:"phone,omitempty"`
	Email string `json:"email,omitempty"`
}

// Metadata is the extension payload of an organisation. Every key is optional;
// unknown keys are rejected on write because the struct is the contract.
type Metadata struct {
	Intro         string   `json:"intro,omitempty"`
	Industry      string   `json:"industry,omitempty"`
	Scale         string   `json:"scale,omitempty"`
	Website       string   `json:"website,omitempty"`
	Address       string   `json:"address,omitempty"`
	CreditCode    string   `json:"credit_code,omitempty"`
	LicenseFileID string   `json:"license_file_id,omitempty"`
	FoundedYear   int      `json:"founded_year,omitempty"`
	Tags          []string `json:"tags,omitempty"`
	Contact       Contact  `json:"contact,omitempty"`
}

// Organization is one institution record.
type Organization struct {
	ID             string     `json:"id"`
	Name           string     `json:"name"`
	ShortName      string     `json:"short_name"`
	OrgType        string     `json:"org_type"`
	LogoFileID     string     `json:"logo_file_id"`
	Province       string     `json:"province"`
	City           string     `json:"city"`
	District       string     `json:"district"`
	Status         Status     `json:"status"`
	RejectReason   string     `json:"reject_reason"`
	OwnerSubjectID string     `json:"owner_subject_id"`
	ReviewedBy     string     `json:"reviewed_by,omitempty"`
	ReviewedAt     *time.Time `json:"reviewed_at,omitempty"`
	Metadata       Metadata   `json:"metadata"`
	CreatedAt      time.Time  `json:"created_at"`
	UpdatedAt      time.Time  `json:"updated_at"`
}

// CreateInput is the accepted payload for a new organisation.
type CreateInput struct {
	Name       string   `json:"name"`
	ShortName  string   `json:"short_name"`
	OrgType    string   `json:"org_type"`
	LogoFileID string   `json:"logo_file_id"`
	Province   string   `json:"province"`
	City       string   `json:"city"`
	District   string   `json:"district"`
	Metadata   Metadata `json:"metadata"`
}

// UpdateInput is a partial update; nil fields are left unchanged.
type UpdateInput struct {
	Name       *string   `json:"name"`
	ShortName  *string   `json:"short_name"`
	OrgType    *string   `json:"org_type"`
	LogoFileID *string   `json:"logo_file_id"`
	Province   *string   `json:"province"`
	City       *string   `json:"city"`
	District   *string   `json:"district"`
	Metadata   *Metadata `json:"metadata"`
}

// ReviewInput changes the review state of an organisation.
type ReviewInput struct {
	Status Status `json:"status"`
	Reason string `json:"reason"`
}

// Filter selects organisations for the listing. Status is only honoured for
// platform-admin review listings; the public path leaves it empty.
type Filter struct {
	City    string
	OrgType string
	Status  Status
	// AllStatuses is only set by the platform-admin review listing; the public
	// path leaves it false and therefore stays verified-only.
	AllStatuses bool
	Query       string
	Limit       int32
	Offset      int32
}

// Member is one subject's membership in an organisation.
type Member struct {
	SubjectID      string    `json:"subject_id"`
	MemberRole     Role      `json:"member_role"`
	OrganizationID string    `json:"organization_id"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
}

// maxRunes bounds every metadata text field so a JSONB payload stays small.
const (
	maxIntroRunes     = 2000
	maxIndustryRunes  = 64
	maxScaleRunes     = 32
	maxWebsiteRunes   = 200
	maxAddressRunes   = 200
	maxCreditCode     = 32
	maxTagRunes       = 16
	maxTags           = 10
	maxContactRunes   = 40
	maxPhoneRunes     = 32
	maxEmailRunes     = 120
	earliestFounded   = 1800
	maxReviewReason   = 200
	maxNameRunes      = 120
	maxShortNameRunes = 60
	maxRegionRunes    = 32
	maxLogoFileID     = 64
)

// Validate checks the create payload and returns its normalised form.
func (in CreateInput) Validate() (CreateInput, error) {
	name, err := text("机构名称", in.Name, 1, maxNameRunes)
	if err != nil {
		return in, err
	}
	in.Name = name

	if in.ShortName, err = text("机构简称", in.ShortName, 0, maxShortNameRunes); err != nil {
		return in, err
	}
	if in.OrgType == "" {
		in.OrgType = OrgTypes[0]
	}
	if !contains(OrgTypes, in.OrgType) {
		return in, apierr.Invalid("机构类型无效")
	}
	if in.LogoFileID, err = text("机构标识", in.LogoFileID, 0, maxLogoFileID); err != nil {
		return in, err
	}
	if in.Province, err = text("省份", in.Province, 0, maxRegionRunes); err != nil {
		return in, err
	}
	if in.City, err = text("城市", in.City, 0, maxRegionRunes); err != nil {
		return in, err
	}
	if in.District, err = text("区县", in.District, 0, maxRegionRunes); err != nil {
		return in, err
	}
	if in.Metadata, err = in.Metadata.Validate(); err != nil {
		return in, err
	}
	return in, nil
}

// Validate checks every metadata field. Unknown keys are rejected by the JSON
// decoder before this runs.
func (m Metadata) Validate() (Metadata, error) {
	var err error

	normalised := Metadata{
		FoundedYear: m.FoundedYear,
	}

	if normalised.Intro, err = text("机构简介", m.Intro, 0, maxIntroRunes); err != nil {
		return m, err
	}
	if normalised.Industry, err = text("所属行业", m.Industry, 0, maxIndustryRunes); err != nil {
		return m, err
	}
	if normalised.Scale, err = text("机构规模", m.Scale, 0, maxScaleRunes); err != nil {
		return m, err
	}
	if normalised.Website, err = text("网站", m.Website, 0, maxWebsiteRunes); err != nil {
		return m, err
	}
	if normalised.Website != "" && !strings.HasPrefix(normalised.Website, "http://") && !strings.HasPrefix(normalised.Website, "https://") {
		return m, apierr.Invalid("网站必须以 http:// 或 https:// 开头")
	}
	if normalised.Address, err = text("详细地址", m.Address, 0, maxAddressRunes); err != nil {
		return m, err
	}
	if normalised.CreditCode, err = text("统一社会信用代码", m.CreditCode, 0, maxCreditCode); err != nil {
		return m, err
	}
	// license_file_id 指向 platformd 共享 files.id，由平台审核时查看营业执照原件。
	if normalised.LicenseFileID, err = text("营业执照", m.LicenseFileID, 0, maxLogoFileID); err != nil {
		return m, err
	}
	if normalised.FoundedYear != 0 {
		latest := time.Now().UTC().Year() + 1
		if normalised.FoundedYear < earliestFounded || normalised.FoundedYear > latest {
			return m, apierr.Invalid("成立年份超出合理范围")
		}
	}
	if normalised.Tags, err = tags(m.Tags); err != nil {
		return m, err
	}
	if normalised.Contact, err = m.Contact.Validate(); err != nil {
		return m, err
	}
	return normalised, nil
}

// Validate checks the contact block.
func (c Contact) Validate() (Contact, error) {
	name, err := text("联系人", c.Name, 0, maxContactRunes)
	if err != nil {
		return c, err
	}
	phone, err := text("联系电话", c.Phone, 0, maxPhoneRunes)
	if err != nil {
		return c, err
	}
	email, err := text("联系邮箱", c.Email, 0, maxEmailRunes)
	if err != nil {
		return c, err
	}
	if email != "" && !validEmail(email) {
		return c, apierr.Invalid("联系邮箱格式无效")
	}
	return Contact{Name: name, Phone: phone, Email: email}, nil
}

// Validate checks and normalises a partial update.
func (in UpdateInput) Validate() (UpdateInput, error) {
	if in.Name != nil {
		value, err := text("机构名称", *in.Name, 1, maxNameRunes)
		if err != nil {
			return in, err
		}
		in.Name = &value
	}
	if in.ShortName != nil {
		value, err := text("机构简称", *in.ShortName, 0, maxShortNameRunes)
		if err != nil {
			return in, err
		}
		in.ShortName = &value
	}
	if in.OrgType != nil {
		if !contains(OrgTypes, *in.OrgType) {
			return in, apierr.Invalid("机构类型无效")
		}
	}
	if in.LogoFileID != nil {
		value, err := text("机构标识", *in.LogoFileID, 0, maxLogoFileID)
		if err != nil {
			return in, err
		}
		in.LogoFileID = &value
	}
	for _, region := range []struct {
		label string
		field **string
	}{
		{"省份", &in.Province},
		{"城市", &in.City},
		{"区县", &in.District},
	} {
		if *region.field == nil {
			continue
		}
		value, err := text(region.label, **region.field, 0, maxRegionRunes)
		if err != nil {
			return in, err
		}
		*region.field = &value
	}
	if in.Metadata != nil {
		value, err := in.Metadata.Validate()
		if err != nil {
			return in, err
		}
		in.Metadata = &value
	}
	return in, nil
}

// Validate checks a review request.
func (in ReviewInput) Validate() (ReviewInput, error) {
	switch in.Status {
	case StatusVerified, StatusRejected, StatusDisabled:
	default:
		return in, apierr.Invalid("审核状态无效")
	}
	reason, err := text("审核说明", in.Reason, 0, maxReviewReason)
	if err != nil {
		return in, err
	}
	in.Reason = reason
	if in.Status == StatusRejected && in.Reason == "" {
		return in, apierr.Invalid("驳回时必须填写原因")
	}
	return in, nil
}

// ValidateMemberRole checks a role assignment.
func ValidateMemberRole(role Role) (Role, error) {
	for _, candidate := range Roles {
		if candidate == role {
			return role, nil
		}
	}
	return "", apierr.Invalid("成员角色无效")
}

// text trims a value and enforces a rune-count range.
func text(label, value string, minimum, maximum int) (string, error) {
	value = strings.TrimSpace(value)
	length := utf8.RuneCountInString(value)
	if length < minimum {
		return "", apierr.Invalid("%s不能为空", label)
	}
	if length > maximum {
		return "", apierr.Invalid("%s不能超过 %d 个字符", label, maximum)
	}
	return value, nil
}

func tags(values []string) ([]string, error) {
	if len(values) > maxTags {
		return nil, apierr.Invalid("标签最多 %d 个", maxTags)
	}
	out := make([]string, 0, len(values))
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		trimmed, err := text("标签", value, 1, maxTagRunes)
		if err != nil {
			return nil, err
		}
		if _, ok := seen[trimmed]; ok {
			continue
		}
		seen[trimmed] = struct{}{}
		out = append(out, trimmed)
	}
	if len(out) == 0 {
		return nil, nil
	}
	return out, nil
}

func contains(values []string, value string) bool {
	for _, candidate := range values {
		if candidate == value {
			return true
		}
	}
	return false
}

func validEmail(value string) bool {
	at := strings.LastIndex(value, "@")
	if at <= 0 || at == len(value)-1 {
		return false
	}
	domain := value[at+1:]
	if !strings.Contains(domain, ".") || strings.ContainsAny(value, " \t\r\n") {
		return false
	}
	return true
}

// ErrNotMember reports a subject that holds no role in the organisation.
var ErrNotMember = errors.New("subject is not a member of the organisation")
