package httpapi

import (
	"encoding/json"
	"net/http"
)

type errorBody struct {
	Error string `json:"error"`
}

type meta struct {
	Total int64 `json:"total"`
}

// listResponse is the shared envelope for every listing endpoint.
type listResponse[T any] struct {
	Records []T  `json:"records"`
	Meta    meta `json:"meta"`
}

func writeList[T any](w http.ResponseWriter, status int, records []T, total int64) {
	if records == nil {
		records = []T{}
	}
	writeJSON(w, status, listResponse[T]{Records: records, Meta: meta{Total: total}})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorBody{Error: message})
}
