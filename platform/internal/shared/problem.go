package shared

import (
	"encoding/json"
	"log/slog"
	"net/http"
)

// 与 service-nexus-shared 的 ApiError 同形（RFC 9457）：
//
//	400 invalid-request        detail 为具体校验信息
//	401 not-authenticated      "not authenticated"
//	403 not-authorized         "not authorized"
//	403 permission-required    "permission required: <code>" + required_permission
//	404 not-found              detail
//	409 conflict               detail
//	413 payload-too-large      "payload too large"
//	500 internal-error         "internal server error"
//
// type 字段为 "<Prefix>/problems/<slug>"，不输出 instance（与 Nexus 一致）。
type problemDetails struct {
	Type               string `json:"type"`
	Title              string `json:"title"`
	Status             int    `json:"status"`
	Detail             string `json:"detail"`
	RequiredPermission string `json:"required_permission,omitempty"`
}

type apiError struct {
	status             int
	slug               string
	detail             string
	requiredPermission string
}

func (err apiError) write(responseWriter http.ResponseWriter) {
	responseWriter.Header().Set("Content-Type", "application/problem+json")
	responseWriter.WriteHeader(err.status)
	_ = json.NewEncoder(responseWriter).Encode(problemDetails{
		Type:               Prefix + "/problems/" + err.slug,
		Title:              http.StatusText(err.status),
		Status:             err.status,
		Detail:             err.detail,
		RequiredPermission: err.requiredPermission,
	})
}

func badRequest(detail string) apiError {
	return apiError{status: http.StatusBadRequest, slug: "invalid-request", detail: detail}
}

func unauthenticated() apiError {
	return apiError{status: http.StatusUnauthorized, slug: "not-authenticated", detail: "not authenticated"}
}

func notFound(detail string) apiError {
	return apiError{status: http.StatusNotFound, slug: "not-found", detail: detail}
}

func conflict(detail string) apiError {
	return apiError{status: http.StatusConflict, slug: "conflict", detail: detail}
}

func payloadTooLarge() apiError {
	return apiError{status: http.StatusRequestEntityTooLarge, slug: "payload-too-large", detail: "payload too large"}
}

func internalError() apiError {
	return apiError{status: http.StatusInternalServerError, slug: "internal-error", detail: "internal server error"}
}

func permissionRequired(permission string) apiError {
	return apiError{
		status:             http.StatusForbidden,
		slug:               "permission-required",
		detail:             "permission required: " + permission,
		requiredPermission: permission,
	}
}

// fail 记录 5xx 并把错误写成 Problem Details。
func (handler *Handler) fail(responseWriter http.ResponseWriter, err apiError) {
	if err.status >= http.StatusInternalServerError {
		handler.logger().LogAttrs(nil, slog.LevelError, "shared request failed", slog.String("detail", err.detail))
	}
	err.write(responseWriter)
}
