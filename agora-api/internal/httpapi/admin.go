package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/intents"
)

// IntentStore persists the Agora identity a subject last chose.
type IntentStore interface {
	Set(ctx context.Context, subjectID string, userType intents.UserType) error
	Ensure(ctx context.Context, subjectID string) error
	List(ctx context.Context, limit, offset int32) ([]string, int64, error)
	BySubjects(ctx context.Context, subjectIDs []string) (map[string]intents.UserType, error)
}

type subjectStatusInput struct {
	Status string `json:"status"`
}

type intentInput struct {
	UserType string `json:"user_type"`
}

// adminSubject is the platformd subject plus the Agora intent, when known.
type adminSubject struct {
	identityclient.Subject
	UserType string `json:"user_type,omitempty"`
}

// saveMyIntent records the job-market identity chosen on the Agora login screen.
// It never grants permissions; organisation membership stays authoritative.
func (deps Deps) saveMyIntent(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if deps.Intents == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "用户类型暂不可用")
		return
	}
	var input intentInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	userType, err := intents.Parse(strings.TrimSpace(input.UserType))
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if err := deps.Intents.Set(request.Context(), identity.SubjectID, userType); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	responseWriter.WriteHeader(http.StatusNoContent)
}

// adminListSubjects proxies platformd's account management list. Only platform
// administrators may read it; the browser never talks to platformd directly.
func (deps Deps) adminListSubjects(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if !identity.PlatformAdmin {
		writeError(responseWriter, http.StatusForbidden, "只有平台管理员可以管理用户")
		return
	}

	if deps.Intents == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "用户目录暂不可用")
		return
	}
	limit, offset, err := parsePagination(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	// subject_intents is the Agora user registry: it only contains subjects that
	// have used Agora, so accounts from other products never appear here.
	subjectIDs, total, err := deps.Intents.List(request.Context(), limit, offset)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if len(subjectIDs) == 0 {
		writeList(responseWriter, http.StatusOK, []adminSubject{}, total)
		return
	}

	subjects, err := deps.Identity.ListSubjectsByIDs(request.Context(), request.Header.Get("Cookie"), subjectIDs)
	if err != nil {
		deps.writeIdentityAdminError(responseWriter, request, err)
		return
	}
	byID := make(map[string]identityclient.Subject, len(subjects))
	for _, subject := range subjects {
		byID[subject.ID] = subject
	}
	userTypes, err := deps.Intents.BySubjects(request.Context(), subjectIDs)
	if err != nil {
		deps.Logger.Warn("load subject types failed", "request_id", requestIDFrom(request.Context()), "error", err)
		userTypes = map[string]intents.UserType{}
	}

	records := make([]adminSubject, 0, len(subjectIDs))
	for _, subjectID := range subjectIDs {
		subject, ok := byID[subjectID]
		if !ok {
			continue
		}
		records = append(records, adminSubject{
			Subject:  subject,
			UserType: string(userTypes[subjectID]),
		})
	}
	writeList(responseWriter, http.StatusOK, records, total)
}

// adminUpdateSubject enables or disables one account through platformd. The
// browser's CSRF token is forwarded; platformd validates it against the session.
func (deps Deps) adminUpdateSubject(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if !identity.PlatformAdmin {
		writeError(responseWriter, http.StatusForbidden, "只有平台管理员可以管理用户")
		return
	}

	var input subjectStatusInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	status := strings.TrimSpace(input.Status)
	if status != "启用" && status != "禁用" {
		writeError(responseWriter, http.StatusBadRequest, "用户状态无效")
		return
	}

	subject, err := deps.Identity.UpdateSubjectStatus(
		request.Context(),
		request.Header.Get("Cookie"),
		request.Header.Get("X-CSRF-Token"),
		request.PathValue("id"),
		status,
	)
	if err != nil {
		deps.writeIdentityAdminError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, subject)
}

// writeIdentityAdminError keeps platformd's status for client errors and hides
// upstream details behind 502 for everything else.
func (deps Deps) writeIdentityAdminError(responseWriter http.ResponseWriter, request *http.Request, err error) {
	var upstream *identityclient.UpstreamError
	if errors.As(err, &upstream) {
		switch upstream.Status {
		case http.StatusBadRequest, http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound, http.StatusConflict:
			writeError(responseWriter, upstream.Status, upstream.Detail)
			return
		}
	}
	deps.Logger.Warn("identity administration request failed", "request_id", requestIDFrom(request.Context()), "error", err)
	writeError(responseWriter, http.StatusBadGateway, "认证服务暂不可用")
}
