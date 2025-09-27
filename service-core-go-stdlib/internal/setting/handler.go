package setting

import (
	"net/http"

	"go.uber.org/zap"
)

// Handler contains dependencies for handling setting endpoints.
type Handler struct {
	logger *zap.SugaredLogger
}

// NewHandler constructs a new Handler.
func NewHandler(logger *zap.SugaredLogger) *Handler {
	return &Handler{logger: logger}
}

// List is a simple example handler that returns an empty JSON array.
func (h *Handler) List(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte("[]"))
}
