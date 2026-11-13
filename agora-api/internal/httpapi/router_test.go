package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type pingerFunc func(context.Context) error

func (f pingerFunc) Ping(ctx context.Context) error { return f(ctx) }

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func newTestHandler(ping pingerFunc) http.Handler {
	return New(Deps{
		Logger:           testLogger(),
		DB:               ping,
		AllowedOrigins:   []string{"http://localhost:4321"},
		ReadinessTimeout: time.Second,
	})
}

func TestHealth(t *testing.T) {
	handler := newTestHandler(func(context.Context) error { return nil })
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}
	if got := response.Header().Get("X-Request-ID"); got == "" {
		t.Fatal("X-Request-ID header is empty")
	}
	if contentType := response.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "application/json") {
		t.Fatalf("Content-Type = %q, want application/json", contentType)
	}

	var body readyResponse
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Status != "ok" {
		t.Fatalf("status body = %q, want ok", body.Status)
	}
}

func TestStatus(t *testing.T) {
	handler := newTestHandler(func(context.Context) error { return nil })
	request := httptest.NewRequest(http.MethodGet, "/crate-api/agora/v1/status", nil)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}

	var body statusResponse
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Service != "agora-api" || body.Status != "ok" {
		t.Fatalf("body = %+v, want service agora-api and status ok", body)
	}
	if _, err := time.Parse(time.RFC3339, body.Time); err != nil {
		t.Fatalf("time = %q: %v", body.Time, err)
	}
}

func TestReadiness(t *testing.T) {
	tests := []struct {
		name       string
		ping       pingerFunc
		wantStatus int
		wantBody   string
	}{
		{
			name:       "database ready",
			ping:       func(context.Context) error { return nil },
			wantStatus: http.StatusOK,
			wantBody:   "ready",
		},
		{
			name:       "database unavailable",
			ping:       func(context.Context) error { return errors.New("connection refused") },
			wantStatus: http.StatusServiceUnavailable,
			wantBody:   "database unavailable",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			handler := newTestHandler(test.ping)
			request := httptest.NewRequest(http.MethodGet, "/readyz", nil)
			response := httptest.NewRecorder()

			handler.ServeHTTP(response, request)

			if response.Code != test.wantStatus {
				t.Fatalf("status = %d, want %d", response.Code, test.wantStatus)
			}
			if !strings.Contains(response.Body.String(), test.wantBody) {
				t.Fatalf("body = %q, want it to contain %q", response.Body.String(), test.wantBody)
			}
		})
	}
}

func TestCORSPreflight(t *testing.T) {
	handler := newTestHandler(func(context.Context) error { return nil })
	request := httptest.NewRequest(http.MethodOptions, "/crate-api/agora/v1/status", nil)
	request.Header.Set("Origin", "http://localhost:4321")
	request.Header.Set("Access-Control-Request-Method", http.MethodGet)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusNoContent)
	}
	if got := response.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:4321" {
		t.Fatalf("Access-Control-Allow-Origin = %q", got)
	}
}

func TestCORSRejectsUnknownOrigin(t *testing.T) {
	handler := newTestHandler(func(context.Context) error { return nil })
	request := httptest.NewRequest(http.MethodOptions, "/crate-api/agora/v1/status", nil)
	request.Header.Set("Origin", "http://evil.example")
	request.Header.Set("Access-Control-Request-Method", http.MethodGet)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusForbidden)
	}
}

func TestMethodNotAllowed(t *testing.T) {
	handler := newTestHandler(func(context.Context) error { return nil })
	request := httptest.NewRequest(http.MethodPost, "/healthz", nil)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusMethodNotAllowed)
	}
}
