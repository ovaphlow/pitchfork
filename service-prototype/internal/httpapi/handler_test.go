package httpapi_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/httpapi"
)

func TestHealthzReturnsJSONHealth(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/crate-api/prototype/v1/healthz", nil)
	response := httptest.NewRecorder()

	httpapi.NewMux().ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}
	if contentType := response.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "application/json") {
		t.Fatalf("content type = %q, want application/json", contentType)
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("body is not valid JSON: %v", err)
	}
	if status, ok := body["status"].(string); !ok || status == "" {
		t.Fatalf("status field = %v, want a non-empty string", body["status"])
	}
}

func TestUnknownPathReturnsNotFound(t *testing.T) {
	for _, path := range []string{"/", "/crate-api/prototype/v1/unknown", "/crate-api/prototype/v1/subjects"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		response := httptest.NewRecorder()

		httpapi.NewMux().ServeHTTP(response, request)

		if response.Code != http.StatusNotFound {
			t.Fatalf("GET %s status = %d, want %d", path, response.Code, http.StatusNotFound)
		}
	}
}

func TestUnsupportedMethodReturnsMethodNotAllowedWithAllow(t *testing.T) {
	request := httptest.NewRequest(http.MethodPost, "/crate-api/prototype/v1/healthz", nil)
	response := httptest.NewRecorder()

	httpapi.NewMux().ServeHTTP(response, request)

	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusMethodNotAllowed)
	}
	if allow := response.Header().Get("Allow"); !strings.Contains(allow, http.MethodGet) {
		t.Fatalf("Allow header = %q, want it to contain %q", allow, http.MethodGet)
	}
}
