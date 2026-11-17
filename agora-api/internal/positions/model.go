// Package positions owns job postings. A posting belongs to exactly one
// organisation and is maintainable by that organisation's members.
package positions

import (
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
)

// Status is the publication state of a posting.
type Status string

// Publication states. 已停招 is written by the store when a 14-day recruitment
// window ends; it is never accepted as a user-requested transition.
const (
	StatusDraft   Status = "草稿"
	StatusOpen    Status = "招聘中"
	StatusPaused  Status = "已暂停"
	StatusStopped Status = "已停招"
	StatusClosed  Status = "已关闭"
)

// Statuses lists every accepted posting state.
var Statuses = []Status{StatusDraft, StatusOpen, StatusPaused, StatusStopped, StatusClosed}

// CanTransitionTo reports whether a state change is allowed. 招聘中 → 已停招
// happens automatically on window expiry; 已停招 and 已关闭 both reopen through
// 招聘中, which the store grants a fresh 7-day recruitment window.
func (s Status) CanTransitionTo(next Status) bool {
	switch s {
	case StatusDraft:
		return next == StatusOpen
	case StatusOpen:
		return next == StatusPaused || next == StatusClosed
	case StatusPaused:
		return next == StatusOpen || next == StatusClosed
	case StatusStopped:
		return next == StatusOpen || next == StatusClosed
	case StatusClosed:
		return next == StatusOpen
	default:
		return false
	}
}

// IsPublic reports whether the posting is visible in the public listing.
func (s Status) IsPublic() bool {
	return s == StatusOpen
}

// Public reports whether the posting is currently visible to anonymous callers.
// Both the recruitment window (OpenUntil) and the employer deadline (ExpiresAt)
// are checked, so a 招聘中 posting past either deadline is no longer public.
func (p Position) Public(now time.Time) bool {
	if p.Status != StatusOpen {
		return false
	}
	if p.OpenUntil != nil && !p.OpenUntil.After(now) {
		return false
	}
	return p.ExpiresAt == nil || p.ExpiresAt.After(now)
}

// EmploymentTypes, WorkModes, Educations and Experiences are the accepted
// vocabularies; they mirror the CHECK constraints in the migrations.
var (
	EmploymentTypes = []string{"全职", "兼职", "实习", "临时", "项目制"}
	WorkModes       = []string{"坐班", "远程", "混合"}
	Educations      = []string{"不限", "高中及以下", "大专", "本科", "硕士", "博士"}
	Experiences     = []string{"不限", "应届生", "1-3年", "3-5年", "5-10年", "10年以上"}
	SalaryUnits     = []string{"时", "日", "月", "年"}
	ApplyMethods    = []string{"email", "link", "phone"}
)

// Contact is the posting contact block stored in metadata.
type Contact struct {
	Name  string `json:"name,omitempty"`
	Phone string `json:"phone,omitempty"`
	Email string `json:"email,omitempty"`
}

// Apply describes how a candidate should apply.
type Apply struct {
	Method string `json:"method,omitempty"`
	Value  string `json:"value,omitempty"`
}

// Metadata is the extension payload of a posting. The struct is the contract:
// unknown keys are rejected on write.
type Metadata struct {
	Description  string   `json:"description,omitempty"`
	Requirements string   `json:"requirements,omitempty"`
	Benefits     string   `json:"benefits,omitempty"`
	Address      string   `json:"address,omitempty"`
	SalaryNote   string   `json:"salary_note,omitempty"`
	Tags         []string `json:"tags,omitempty"`
	Attachments  []string `json:"attachments,omitempty"`
	Contact      Contact  `json:"contact,omitempty"`
	Apply        Apply    `json:"apply,omitempty"`
}

// Position is one job posting joined with its organisation name.
type Position struct {
	ID               string     `json:"id"`
	OrganizationID   string     `json:"organization_id"`
	OrganizationName string     `json:"organization_name"`
	Title            string     `json:"title"`
	Category         string     `json:"category"`
	EmploymentType   string     `json:"employment_type"`
	WorkMode         string     `json:"work_mode"`
	Headcount        int        `json:"headcount"`
	SalaryNegotiable bool       `json:"salary_negotiable"`
	SalaryMin        *int       `json:"salary_min,omitempty"`
	SalaryMax        *int       `json:"salary_max,omitempty"`
	SalaryUnit       string     `json:"salary_unit"`
	Education        string     `json:"education"`
	Experience       string     `json:"experience"`
	Province         string     `json:"province"`
	City             string     `json:"city"`
	District         string     `json:"district"`
	Status           Status     `json:"status"`
	PublishedAt      *time.Time `json:"published_at,omitempty"`
	ExpiresAt        *time.Time `json:"expires_at,omitempty"`
	OpenUntil        *time.Time `json:"open_until,omitempty"`
	ViewCount        int64      `json:"view_count"`
	CreatedBy        string     `json:"created_by"`
	Metadata         Metadata   `json:"metadata"`
	CreatedAt        time.Time  `json:"created_at"`
	UpdatedAt        time.Time  `json:"updated_at"`
}

// CreateInput is the accepted payload for a new posting.
type CreateInput struct {
	OrganizationID   string     `json:"organization_id"`
	Title            string     `json:"title"`
	Category         string     `json:"category"`
	EmploymentType   string     `json:"employment_type"`
	WorkMode         string     `json:"work_mode"`
	Headcount        int        `json:"headcount"`
	SalaryNegotiable bool       `json:"salary_negotiable"`
	SalaryMin        *int       `json:"salary_min"`
	SalaryMax        *int       `json:"salary_max"`
	SalaryUnit       string     `json:"salary_unit"`
	Education        string     `json:"education"`
	Experience       string     `json:"experience"`
	Province         string     `json:"province"`
	City             string     `json:"city"`
	District         string     `json:"district"`
	ExpiresAt        *time.Time `json:"expires_at"`
	Metadata         Metadata   `json:"metadata"`
}

// UpdateInput is a partial update; nil fields are left unchanged.
type UpdateInput struct {
	Title            *string    `json:"title"`
	Category         *string    `json:"category"`
	EmploymentType   *string    `json:"employment_type"`
	WorkMode         *string    `json:"work_mode"`
	Headcount        *int       `json:"headcount"`
	SalaryNegotiable *bool      `json:"salary_negotiable"`
	SalaryMin        *int       `json:"salary_min"`
	SalaryMax        *int       `json:"salary_max"`
	SalaryUnit       *string    `json:"salary_unit"`
	Education        *string    `json:"education"`
	Experience       *string    `json:"experience"`
	Province         *string    `json:"province"`
	City             *string    `json:"city"`
	District         *string    `json:"district"`
	ExpiresAt        *time.Time `json:"expires_at"`
	Metadata         *Metadata  `json:"metadata"`
}

// StatusInput changes the publication state.
type StatusInput struct {
	Status Status `json:"status"`
}

// Filter selects postings for the public listing.
type Filter struct {
	OrganizationID string
	City           string
	Category       string
	EmploymentType string
	Education      string
	Experience     string
	// MinimumSalary keeps postings whose upper bound is at least this value.
	// Salary-negotiable postings always match.
	MinimumSalary int
	Query         string
	Limit         int32
	Offset        int32
}

const (
	maxTitleRunes        = 200
	maxCategoryRunes     = 64
	maxRegionRunes       = 32
	maxDescriptionRunes  = 20000
	maxRequirementsRunes = 20000
	maxBenefitsRunes     = 5000
	maxAddressRunes      = 200
	maxSalaryNoteRunes   = 200
	maxTagRunes          = 16
	maxTags              = 10
	maxAttachments       = 10
	maxAttachmentRunes   = 64
	maxContactRunes      = 40
	maxPhoneRunes        = 32
	maxEmailRunes        = 120
	maxApplyValueRunes   = 200
	maxHeadcount         = 999
	maxSalary            = 100_000_000
)

// Validate checks and normalises the create payload.
func (in CreateInput) Validate() (CreateInput, error) {
	var err error

	in.OrganizationID = strings.TrimSpace(in.OrganizationID)
	if in.OrganizationID == "" {
		return in, apierr.Invalid("必须指定所属机构")
	}
	if in.Title, err = text("岗位名称", in.Title, 1, maxTitleRunes); err != nil {
		return in, err
	}
	if in.Category, err = text("岗位类别", in.Category, 0, maxCategoryRunes); err != nil {
		return in, err
	}
	if in.EmploymentType, err = vocabulary("用工类型", in.EmploymentType, EmploymentTypes); err != nil {
		return in, err
	}
	if in.WorkMode, err = vocabulary("工作方式", in.WorkMode, WorkModes); err != nil {
		return in, err
	}
	if in.Education, err = vocabulary("学历要求", in.Education, Educations); err != nil {
		return in, err
	}
	if in.Experience, err = vocabulary("经验要求", in.Experience, Experiences); err != nil {
		return in, err
	}
	if in.SalaryUnit, err = vocabulary("薪资单位", in.SalaryUnit, SalaryUnits); err != nil {
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
	if in.Headcount, err = headcount(in.Headcount); err != nil {
		return in, err
	}
	if in.SalaryMin, in.SalaryMax, err = salary(in.SalaryNegotiable, in.SalaryMin, in.SalaryMax); err != nil {
		return in, err
	}
	if in.Metadata, err = in.Metadata.Validate(); err != nil {
		return in, err
	}
	return in, nil
}

// Validate checks and normalises a partial update. The salary pair is resolved
// by the store against the stored row, because a patch may send only one end.
func (in UpdateInput) Validate() (UpdateInput, error) {
	if in.Title != nil {
		value, err := text("岗位名称", *in.Title, 1, maxTitleRunes)
		if err != nil {
			return in, err
		}
		in.Title = &value
	}
	if in.Category != nil {
		value, err := text("岗位类别", *in.Category, 0, maxCategoryRunes)
		if err != nil {
			return in, err
		}
		in.Category = &value
	}
	for _, enum := range []struct {
		label   string
		field   **string
		allowed []string
	}{
		{"用工类型", &in.EmploymentType, EmploymentTypes},
		{"工作方式", &in.WorkMode, WorkModes},
		{"学历要求", &in.Education, Educations},
		{"经验要求", &in.Experience, Experiences},
	} {
		if *enum.field == nil {
			continue
		}
		value, err := vocabulary(enum.label, **enum.field, enum.allowed)
		if err != nil {
			return in, err
		}
		*enum.field = &value
	}
	if in.SalaryUnit != nil {
		value, err := vocabulary("薪资单位", *in.SalaryUnit, SalaryUnits)
		if err != nil {
			return in, err
		}
		in.SalaryUnit = &value
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
	if in.Headcount != nil {
		value, err := headcount(*in.Headcount)
		if err != nil {
			return in, err
		}
		in.Headcount = &value
	}
	for label, value := range map[string]*int{"薪资下限": in.SalaryMin, "薪资上限": in.SalaryMax} {
		if value == nil {
			continue
		}
		if *value < 0 || *value > maxSalary {
			return in, apierr.Invalid("%s超出合理范围", label)
		}
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

// Validate checks a state change request.
func (in StatusInput) Validate(current Status) (Status, error) {
	next := Status(strings.TrimSpace(string(in.Status)))
	for _, candidate := range Statuses {
		if candidate == next {
			if !current.CanTransitionTo(next) {
				return "", apierr.Invalid("岗位状态不能从「%s」变为「%s」", current, next)
			}
			return next, nil
		}
	}
	return "", apierr.Invalid("岗位状态无效")
}

// Validate checks every metadata field of a posting.
func (m Metadata) Validate() (Metadata, error) {
	var err error

	normalised := Metadata{}
	if normalised.Description, err = text("岗位职责", m.Description, 0, maxDescriptionRunes); err != nil {
		return m, err
	}
	if normalised.Requirements, err = text("任职要求", m.Requirements, 0, maxRequirementsRunes); err != nil {
		return m, err
	}
	if normalised.Benefits, err = text("福利待遇", m.Benefits, 0, maxBenefitsRunes); err != nil {
		return m, err
	}
	if normalised.Address, err = text("工作地址", m.Address, 0, maxAddressRunes); err != nil {
		return m, err
	}
	if normalised.SalaryNote, err = text("薪资说明", m.SalaryNote, 0, maxSalaryNoteRunes); err != nil {
		return m, err
	}
	if normalised.Tags, err = tags(m.Tags); err != nil {
		return m, err
	}
	if normalised.Attachments, err = attachments(m.Attachments); err != nil {
		return m, err
	}
	if normalised.Contact, err = m.Contact.Validate(); err != nil {
		return m, err
	}
	if normalised.Apply, err = m.Apply.Validate(); err != nil {
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

// Validate checks the apply block.
func (a Apply) Validate() (Apply, error) {
	method, err := text("投递方式", a.Method, 0, 16)
	if err != nil {
		return a, err
	}
	value, err := text("投递目标", a.Value, 0, maxApplyValueRunes)
	if err != nil {
		return a, err
	}
	if method == "" && value == "" {
		return Apply{}, nil
	}
	if method == "" || value == "" {
		return a, apierr.Invalid("投递方式与投递目标必须同时填写")
	}
	if !contains(ApplyMethods, method) {
		return a, apierr.Invalid("投递方式无效")
	}
	if method == "email" && !validEmail(value) {
		return a, apierr.Invalid("投递邮箱格式无效")
	}
	return Apply{Method: method, Value: value}, nil
}

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

func vocabulary(label, value string, allowed []string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return allowed[0], nil
	}
	if !contains(allowed, value) {
		return "", apierr.Invalid("%s无效", label)
	}
	return value, nil
}

func headcount(value int) (int, error) {
	if value == 0 {
		return 1, nil
	}
	if value < 1 || value > maxHeadcount {
		return 0, apierr.Invalid("招聘人数必须在 1 到 %d 之间", maxHeadcount)
	}
	return value, nil
}

func salary(negotiable bool, minimum, maximum *int) (*int, *int, error) {
	if negotiable {
		if minimum != nil || maximum != nil {
			return nil, nil, apierr.Invalid("面议岗位不能填写薪资区间")
		}
		return nil, nil, nil
	}
	if minimum == nil {
		return nil, nil, apierr.Invalid("非面议岗位必须填写薪资下限")
	}
	if *minimum < 0 || *minimum > maxSalary {
		return nil, nil, apierr.Invalid("薪资下限超出合理范围")
	}
	if maximum != nil {
		if *maximum < 0 || *maximum > maxSalary {
			return nil, nil, apierr.Invalid("薪资上限超出合理范围")
		}
		if *maximum < *minimum {
			return nil, nil, apierr.Invalid("薪资上限不能低于下限")
		}
	}
	return minimum, maximum, nil
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

func attachments(values []string) ([]string, error) {
	if len(values) > maxAttachments {
		return nil, apierr.Invalid("附件最多 %d 个", maxAttachments)
	}
	out := make([]string, 0, len(values))
	for _, value := range values {
		trimmed, err := text("附件标识", value, 1, maxAttachmentRunes)
		if err != nil {
			return nil, err
		}
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
	if !strings.Contains(value[at+1:], ".") || strings.ContainsAny(value, " \t\r\n") {
		return false
	}
	return true
}
