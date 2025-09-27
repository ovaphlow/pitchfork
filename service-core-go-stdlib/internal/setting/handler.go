package setting

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"

	"go.uber.org/zap"

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
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte("[]"))
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
