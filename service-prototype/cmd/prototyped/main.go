package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/config"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/database"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/examrecords"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/httpapi"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/progress"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// databaseBootstrapTimeout bounds the startup connection attempt so an
// unreachable database delays startup only briefly.
const databaseBootstrapTimeout = 5 * time.Second

func main() {
	runContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	os.Exit(run(runContext, os.Getenv, os.Stdout, os.Stderr))
}

// run is the composition root, separated from main so the startup flow is
// testable with an injected lookup and writers. It returns the process
// exit code.
func run(ctx context.Context, lookup func(string) string, stdout, stderr io.Writer) int {
	configuration, err := config.LoadFromLookup(lookup)
	if err != nil {
		slog.New(slog.NewTextHandler(stderr, nil)).Error("load configuration", "error", err)
		return 1
	}

	logger := slog.New(slog.NewTextHandler(stdout, nil))
	logger.Info("starting prototyped", "port", configuration.Port)

	connector := database.New(configuration.DatabaseURL)
	// An unavailable database must never prevent startup: the failure is
	// logged and the service keeps serving without a database. A later
	// Connect call (or restart) retries the migrations.
	bootstrapDatabase(ctx, logger, connector)
	defer connector.Close()

	// The four built-in drill scenarios (with their steps and assessment
	// points) are seeded on the shared in-memory store before the router
	// is built, so the first request already sees them. The seed is
	// idempotent (dedupe by scenario name) and a failure must never
	// prevent startup: it is logged and the service keeps serving
	// whatever the store holds, mirroring bootstrapDatabase.
	drillStore := drills.NewInMemoryStore()
	if err := drills.Seed(ctx, drills.NewService(drillStore)); err != nil {
		logger.Warn("seed drill scenarios", "error", err)
	}

	// The fifteen built-in evaluation indicators (6 dimensions × 15
	// indicators, demo flags separating the seven computable from the
	// eight presentation rows) are seeded on the shared in-memory store
	// before the router is built, so the first request already sees the
	// dictionary. The seed is idempotent (dedupe by indicator title)
	// and a failure must never prevent startup: it is logged and the
	// service keeps serving whatever the store holds, mirroring the
	// drill seed and bootstrapDatabase.
	evaluationStore := evaluation.NewInMemoryStore()
	if err := evaluation.Seed(ctx, evaluation.NewService(evaluationStore)); err != nil {
		logger.Warn("seed evaluation indicators", "error", err)
	}

	server := &http.Server{
		Addr: configuration.Address(),
		// The prototype runs courses, chapters, questions, assignments,
		// learning progress, exam papers, online exam records, drill
		// scenario templates, dispatch command sessions, the opinion
		// event configurations and the evaluation indicator dictionary
		// on in-memory stores; a database-backed store replaces this at
		// the composition root once the slices land on a real backend.
		// The drill store is shared with the startup seed above; the
		// dispatch store backs the command session of each drill run;
		// the opinion store backs the opinion event configuration of
		// each drill run (and the run-deletion cascade through the
		// drills service's run-opinion cleaner hook); the evaluation
		// store is shared with the indicator seed above.
		Handler: httpapi.NewMux(
			configuration.CORSAllowedOrigins,
			courses.NewInMemoryStore(),
			chapters.NewInMemoryStore(),
			questions.NewInMemoryStore(),
			assignments.NewInMemoryStore(),
			progress.NewInMemoryStore(),
			papers.NewInMemoryStore(),
			examrecords.NewInMemoryStore(),
			drillStore,
			dispatch.NewInMemoryStore(),
			opinion.NewInMemoryStore(),
			evaluationStore,
		),
		ReadHeaderTimeout: 5 * time.Second,
	}

	serverErrors := make(chan error, 1)
	go func() {
		serverErrors <- server.ListenAndServe()
	}()

	select {
	case err := <-serverErrors:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("serve HTTP", "error", err)
			return 1
		}
	case <-ctx.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			logger.Error("shutdown HTTP", "error", err)
			return 1
		}
	}
	return 0
}

// bootstrapDatabase attempts to connect and run the embedded migrations
// within a bounded window. Connection and migration failures are logged and
// swallowed: the service starts and keeps serving even without a database.
func bootstrapDatabase(ctx context.Context, logger *slog.Logger, connector *database.Connector) {
	connectContext, cancel := context.WithTimeout(ctx, databaseBootstrapTimeout)
	defer cancel()
	if err := connector.Connect(connectContext); err != nil {
		logger.Warn("database unavailable; continuing without database", "error", err)
		return
	}
	logger.Info("database ready")
}
