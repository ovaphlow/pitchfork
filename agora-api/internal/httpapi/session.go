package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
)

const (
	defaultPageSize = 20
	maxPageSize     = 100
)

// authenticate resolves the browser session into an identity. The IDP cookie is
// forwarded untouched; agora-api never inspects the token.
func (deps Deps) authenticate(request *http.Request) (identityclient.Identity, error) {
	if deps.Identity == nil {
		return identityclient.Identity{}, &apierr.Unauthorized{}
	}

	cookieHeader := strings.TrimSpace(request.Header.Get("Cookie"))
	if cookieHeader == "" {
		return identityclient.Identity{}, &apierr.Unauthorized{}
	}

	identity, err := deps.Identity.CurrentSession(request.Context(), cookieHeader)
	if err != nil {
		var upstream *identityclient.UpstreamError
		if errors.As(err, &upstream) {
			switch upstream.Status {
			case http.StatusUnauthorized, http.StatusNotFound:
				return identityclient.Identity{}, &apierr.Unauthorized{}
			}
		}
		deps.Logger.Warn("identity request failed", "request_id", requestIDFrom(request.Context()), "error", err)
		return identityclient.Identity{}, err
	}
	if identity.Access != "完整" {
		return identityclient.Identity{}, &apierr.Forbidden{Message: "请先修改密码后再访问"}
	}
	return identity, nil
}

// optionalIdentity reports the caller's identity when a session is present. It
// never fails the request: anonymous callers simply get false.
func (deps Deps) optionalIdentity(request *http.Request) (identityclient.Identity, bool) {
	identity, err := deps.authenticate(request)
	if err != nil {
		return identityclient.Identity{}, false
	}
	return identity, true
}

// writeStoreError maps domain errors onto HTTP responses.
func (deps Deps) writeStoreError(responseWriter http.ResponseWriter, request *http.Request, err error) {
	if message, ok := apierr.ValidationMessage(err); ok {
		writeError(responseWriter, http.StatusBadRequest, message)
		return
	}
	if apierr.IsNotFound(err) {
		writeError(responseWriter, http.StatusNotFound, err.Error())
		return
	}
	if apierr.IsConflict(err) {
		writeError(responseWriter, http.StatusConflict, err.Error())
		return
	}
	if apierr.IsForbidden(err) {
		writeError(responseWriter, http.StatusForbidden, err.Error())
		return
	}
	if apierr.IsUnauthorized(err) {
		writeError(responseWriter, http.StatusUnauthorized, "请先登录")
		return
	}

	deps.Logger.Error("store request failed", "request_id", requestIDFrom(request.Context()), "error", err)
	writeError(responseWriter, http.StatusInternalServerError, "服务暂时不可用，请稍后重试")
}

// parsePagination reads limit/offset, clamping them to a bounded window.
func parsePagination(request *http.Request) (int32, int32, error) {
	limit, err := queryInt(request, "limit", defaultPageSize)
	if err != nil {
		return 0, 0, err
	}
	if limit < 1 || limit > maxPageSize {
		return 0, 0, apierr.Invalid("limit 必须在 1 到 %d 之间", maxPageSize)
	}

	offset, err := queryInt(request, "offset", 0)
	if err != nil {
		return 0, 0, err
	}
	if offset < 0 {
		return 0, 0, apierr.Invalid("offset 不能为负数")
	}
	return int32(limit), int32(offset), nil
}

func queryInt(request *http.Request, name string, fallback int) (int, error) {
	raw := strings.TrimSpace(request.URL.Query().Get(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, apierr.Invalid("%s 必须是整数", name)
	}
	return value, nil
}

// decodeBody reads one JSON object, rejecting unknown fields and trailing data.
func decodeBody(responseWriter http.ResponseWriter, request *http.Request, destination any) error {
	return decodeAuthJSON(responseWriter, request, destination)
}
