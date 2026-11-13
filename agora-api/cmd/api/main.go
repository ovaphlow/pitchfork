package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/config"
	"github.com/ovaphlow/pitchfork/agora-api/internal/database"
	"github.com/ovaphlow/pitchfork/agora-api/internal/httpapi"
	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/logging"
	"github.com/ovaphlow/pitchfork/agora-api/internal/migrate"
	"github.com/ovaphlow/pitchfork/agora-api/internal/nexusclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
	"github.com/ovaphlow/pitchfork/agora-api/internal/positions"
	"github.com/ovaphlow/pitchfork/agora-api/migrations"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "agora-api: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	logger, closeLog, err := logging.New(logging.Config{
		Level:   cfg.LogLevel,
		File:    cfg.LogFile,
		Service: "agora-api",
	})
	if err != nil {
		return fmt.Errorf("init logger: %w", err)
	}
	defer func() {
		if err := closeLog.Close(); err != nil {
			fmt.Fprintf(os.Stderr, "agora-api: close log: %v\n", err)
		}
	}()

	pool, err := database.Open(context.Background(), database.Config{
		URL:             cfg.DatabaseURL,
		MaxConns:        cfg.DBMaxConns,
		ConnectTimeout:  cfg.DBConnectTimeout,
		ApplicationName: "agora-api",
	})
	if err != nil {
		return fmt.Errorf("connect database: %w", err)
	}
	defer pool.Close()

	if cfg.AutoMigrate {
		if err := migrate.Run(context.Background(), pool, migrations.FS, logger); err != nil {
			return fmt.Errorf("apply migrations: %w", err)
		}
	}

	organizationStore := organizations.NewStore(pool)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	handler := httpapi.New(httpapi.Deps{
		Logger:           logger,
		DB:               pool,
		AllowedOrigins:   cfg.AllowedOrigins,
		ReadinessTimeout: cfg.ReadinessTimeout,
		Identity:         identityclient.New(cfg.IdentityBaseURL, cfg.AuthHTTPTimeout),
		Nexus:            nexusclient.New(cfg.NexusBaseURL, cfg.AuthHTTPTimeout),
		Files:            nexusclient.New(cfg.NexusBaseURL, cfg.FilesHTTPTimeout),
		TrustedProxies:   cfg.TrustedProxies,
		Organizations:    organizationStore,
		Positions:        positions.NewStore(pool),

		EmailLinkRedirectURI: cfg.EmailLinkRedirectURI,
	})

	server := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}

	serverErr := make(chan error, 1)
	go func() {
		logger.Info("http server listening", "addr", cfg.HTTPAddr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	select {
	case err := <-serverErr:
		return fmt.Errorf("http server: %w", err)
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("shutdown http server: %w", err)
	}

	logger.Info("http server stopped")
	return nil
}
