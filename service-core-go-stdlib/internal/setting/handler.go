package setting

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"go.uber.org/zap"

	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting/entity"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting/repo"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/pkg/database"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/pkg/utilities"
)

// Handler contains dependencies for handling setting endpoints.
type Handler struct {
	logger *zap.SugaredLogger
	svc    *Service
	db     *sql.DB
}

// NewHandler constructs a new Handler and internally creates the DB, repo and service.
// Note: This makes Handler responsible for wiring a default Service implementation.
func NewHandler(logger *zap.SugaredLogger) *Handler {
	cfg := database.ConfigFromEnv()
	db, err := database.Connect(cfg)
	if err != nil {
		// If DB can't be opened, log and return a handler with nil service.
		// Caller should ensure process terminates or handles this case.
		logger.Errorf("failed to connect to db: %v", err)
		return &Handler{logger: logger}
	}

	r := repo.NewRepo(db)
	svc := NewService(r)
	return &Handler{logger: logger, svc: svc, db: db}
}

// List is a simple example handler that returns an empty JSON array.
func (h *Handler) List(w http.ResponseWriter, r *http.Request) {
	if h.svc == nil {
		http.Error(w, "service unavailable", http.StatusServiceUnavailable)
		return
	}
	q := r.URL.Query()
	category := q.Get("category")
	parentID := q.Get("parent")
	rootID := q.Get("root")
	limit := 50
	offset := 0
	if l := q.Get("limit"); l != "" {
		if v, err := strconv.Atoi(l); err == nil {
			limit = v
		}
	}
	if o := q.Get("offset"); o != "" {
		if v, err := strconv.Atoi(o); err == nil {
			offset = v
		}
	}
	items, err := h.svc.List(r.Context(), category, parentID, rootID, limit, offset)
	if err != nil {
		h.logger.Errorf("failed to list settings: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(items)
}

// Get returns a setting by id using path suffix e.g. /pitchfork-api/settings/{id}
func (h *Handler) Get(w http.ResponseWriter, r *http.Request) {
	if h.svc == nil {
		http.Error(w, "service unavailable", http.StatusServiceUnavailable)
		return
	}
	prefix := "/pitchfork-api/settings/"
	if !strings.HasPrefix(r.URL.Path, prefix) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, prefix)
	if id == "" {
		http.Error(w, "id required", http.StatusBadRequest)
		return
	}
	s, err := h.svc.Get(r.Context(), id)
	if err != nil {
		if errors.Is(err, ErrNotFound) || err == sql.ErrNoRows {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		h.logger.Errorf("failed to get setting: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s)
}

// Create creates a new setting (POST /pitchfork-api/settings)
func (h *Handler) Create(w http.ResponseWriter, r *http.Request) {
	if h.svc == nil {
		http.Error(w, "service unavailable", http.StatusServiceUnavailable)
		return
	}
	var in entity.Setting
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	if in.ID == "" {
		in.ID = utilities.NewKSUID()
	}
	created, err := h.svc.Create(r.Context(), &in)
	if err != nil {
		h.logger.Errorf("failed to create setting: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(created)
}

// Update updates an existing setting using PUT /pitchfork-api/settings/{id}
func (h *Handler) Update(w http.ResponseWriter, r *http.Request) {
	if h.svc == nil {
		http.Error(w, "service unavailable", http.StatusServiceUnavailable)
		return
	}
	prefix := "/pitchfork-api/settings/"
	if !strings.HasPrefix(r.URL.Path, prefix) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, prefix)
	if id == "" {
		http.Error(w, "id required", http.StatusBadRequest)
		return
	}
	var in entity.Setting
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	in.ID = id
	updated, err := h.svc.Update(r.Context(), &in)
	if err != nil {
		h.logger.Errorf("failed to update setting: %v", err)
		switch {
		case errors.Is(err, ErrNotFound):
			http.Error(w, "not found", http.StatusNotFound)
			return
		case errors.Is(err, ErrVersionConflict):
			http.Error(w, "version conflict", http.StatusConflict)
			return
		default:
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(updated)
}

// Delete removes a setting (DELETE /pitchfork-api/settings/{id})
func (h *Handler) Delete(w http.ResponseWriter, r *http.Request) {
	if h.svc == nil {
		http.Error(w, "service unavailable", http.StatusServiceUnavailable)
		return
	}
	prefix := "/pitchfork-api/settings/"
	if !strings.HasPrefix(r.URL.Path, prefix) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, prefix)
	if id == "" {
		http.Error(w, "id required", http.StatusBadRequest)
		return
	}
	if err := h.svc.Delete(r.Context(), id); err != nil {
		h.logger.Errorf("failed to delete setting: %v", err)
		if errors.Is(err, ErrNotFound) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ID returns a generated ID. Query param 'type' selects 'ksuid' or 'snowflake' (default 'ksuid').
// For snowflake, an optional 'node' query param overrides the environment node ID.
func (h *Handler) ID(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	t := q.Get("type")
	if t == "" {
		t = "ksuid"
	}

	var id string
	switch t {
	case "snowflake":
		nodeStr := q.Get("node")
		if nodeStr != "" {
			if n, err := strconv.ParseInt(nodeStr, 10, 64); err == nil {
				id = utilities.NewSnowflakeIDWithNode(n)
				break
			}
			// fallthrough to default if parse fails
		}
		id = utilities.NewSnowflakeID()
	default:
		id = utilities.NewKSUID()
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"id": id})
}
