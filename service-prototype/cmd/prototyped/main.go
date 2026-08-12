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

	"github.com/ovaphlow/pitchfork/service-prototype/internal/config"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/database"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/httpapi"
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

	server := &http.Server{
		Addr:              configuration.Address(),
		Handler:           httpapi.NewMux(),
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
