package shared

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
)

// maxJSONBodyBytes 是单个 JSON 请求体的上限；文件上传走 MaxUploadBytes。
const maxJSONBodyBytes = 1 << 20

// writeJSON 输出 JSON 响应（与 axum Json 一致：Content-Type: application/json）。
func writeJSON(responseWriter http.ResponseWriter, statusCode int, value any) {
	responseWriter.Header().Set("Content-Type", "application/json")
	responseWriter.WriteHeader(statusCode)
	if value == nil {
		return
	}
	_ = json.NewEncoder(responseWriter).Encode(value)
}

// writeNoContent 输出 204。
func writeNoContent(responseWriter http.ResponseWriter) {
	responseWriter.WriteHeader(http.StatusNoContent)
}

// decodeJSON 解析 JSON 请求体。未知字段忽略（与 serde 默认一致），
// 语法错误或类型不符返回 400 invalid-request。
func decodeJSON(request *http.Request, destination any) apiError {
	body, err := io.ReadAll(io.LimitReader(request.Body, maxJSONBodyBytes+1))
	if err != nil {
		return badRequest("could not read request body")
	}
	if len(body) > maxJSONBodyBytes {
		return payloadTooLarge()
	}
	if len(strings.TrimSpace(string(body))) == 0 {
		return badRequest("request body is empty")
	}
	if err := json.Unmarshal(body, destination); err != nil {
		return badRequest("invalid request body")
	}
	return apiError{}
}

// pageQuery 解析 Nexus 口径的分页参数：page 默认 1、page_size 默认 20、上限 100。
func pageQuery(request *http.Request) (limit int64, offset int64, err apiError) {
	const (
		defaultPageSize int64 = 20
		maximumPageSize int64 = 100
	)
	query := request.URL.Query()
	page := int64(1)
	if raw := strings.TrimSpace(query.Get("page")); raw != "" {
		parsed, parseErr := strconv.ParseInt(raw, 10, 64)
		if parseErr != nil {
			return 0, 0, badRequest("page must be positive and page_size must be between 1 and 100")
		}
		page = parsed
	}
	pageSize := defaultPageSize
	if raw := strings.TrimSpace(query.Get("page_size")); raw != "" {
		parsed, parseErr := strconv.ParseInt(raw, 10, 64)
		if parseErr != nil {
			return 0, 0, badRequest("page must be positive and page_size must be between 1 and 100")
		}
		pageSize = parsed
	}
	if page < 1 || pageSize < 1 || pageSize > maximumPageSize {
		return 0, 0, badRequest("page must be positive and page_size must be between 1 and 100")
	}
	return pageSize, (page - 1) * pageSize, apiError{}
}

// boolQuery 读取可选布尔查询参数（"true"/"false"）。
func boolQuery(request *http.Request, name string) (bool, apiError) {
	raw := strings.TrimSpace(request.URL.Query().Get(name))
	if raw == "" {
		return false, apiError{}
	}
	switch strings.ToLower(raw) {
	case "true":
		return true, apiError{}
	case "false":
		return false, apiError{}
	default:
		return false, badRequest(name + " must be true or false")
	}
}

// isNoRows 判断 sql.ErrNoRows。
func isNoRows(err error) bool {
	return errors.Is(err, errNoRows)
}
