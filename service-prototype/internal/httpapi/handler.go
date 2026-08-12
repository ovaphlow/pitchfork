// Package httpapi wires the HTTP routing table for prototyped.
package httpapi

import (
	"encoding/json"
	"net/http"
)

const prototypePrefix = "/crate-api/prototype/v1"

// NewMux builds the HTTP routing table for the prototype service.
func NewMux() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET "+prototypePrefix+"/healthz", health)
	return mux
}

type healthResponse struct {
	Status string `json:"status"`
}

func health(responseWriter http.ResponseWriter, request *http.Request) {
	writeJSON(responseWriter, http.StatusOK, healthResponse{Status: "ok"})
}

func writeJSON(responseWriter http.ResponseWriter, statusCode int, value any) {
	responseWriter.Header().Set("Content-Type", "application/json; charset=utf-8")
	responseWriter.WriteHeader(statusCode)
	_ = json.NewEncoder(responseWriter).Encode(value)
}
