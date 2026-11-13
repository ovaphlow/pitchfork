package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCorsHandlerAllowsConfiguredOriginAndPreflight(t *testing.T) {
	next := http.HandlerFunc(func(responseWriter http.ResponseWriter, request *http.Request) {
		responseWriter.WriteHeader(http.StatusOK)
	})
	handler := corsHandler{next: next, allowedOrigins: []string{"http://localhost:4324"}}
	request := httptest.NewRequest(http.MethodOptions, "/crate-api/identity/v1/session", nil)
	request.Header.Set("Origin", "http://localhost:4324")
	request.Header.Set("Access-Control-Request-Method", http.MethodPost)
	request.Header.Set("Access-Control-Request-Headers", "content-type,x-csrf-token")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusNoContent)
	}
	if response.Header().Get("Access-Control-Allow-Origin") != "http://localhost:4324" {
		t.Fatalf("allow origin = %q", response.Header().Get("Access-Control-Allow-Origin"))
	}
	if response.Header().Get("Access-Control-Allow-Credentials") != "true" {
		t.Fatalf("allow credentials = %q", response.Header().Get("Access-Control-Allow-Credentials"))
	}
	if response.Header().Get("Access-Control-Allow-Headers") == "" {
		t.Fatal("allow headers is empty")
	}
}

func TestCorsHandlerRejectsUnconfiguredOrigin(t *testing.T) {
	next := http.HandlerFunc(func(responseWriter http.ResponseWriter, request *http.Request) {
		responseWriter.WriteHeader(http.StatusOK)
	})
	handler := corsHandler{next: next, allowedOrigins: []string{"http://localhost:4324"}}
	request := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/session", nil)
	request.Header.Set("Origin", "http://evil.example")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}
	if response.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("unexpected allow origin for an unconfigured origin")
	}
}
