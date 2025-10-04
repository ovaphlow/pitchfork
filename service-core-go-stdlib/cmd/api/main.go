package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/joho/godotenv"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/router"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/pkg/database"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/pkg/utilities"
)

func main() {
	// load .env file if present so os.Getenv picks values from it
	// this is best-effort: if no .env exists, continue (use defaults or real env)
	_ = godotenv.Load()

	// init logger
	lg, err := utilities.Init(utilities.ConfigFromEnv())
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to init logger: %v\n", err)
		os.Exit(1)
	}
	defer lg.Sync()

	sugar := lg.Sugar()
	sugar.Info("starting service-core-go-stdlib")

	// init db
	cfg := database.ConfigFromEnv()
	sqlDB, err := database.Connect(cfg)
	if err != nil {
		sugar.Fatalf("db connect: %v", err)
	}
	defer sqlDB.Close()

	// wrap with sqlx for convenience in repos/services
	sqlxDB := sqlx.NewDb(sqlDB, "postgres")
	defer sqlxDB.Close()

	// graceful shutdown
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	sugar.Info("service is running; press Ctrl+C to stop")

	// mount http server
	handler := router.RegisterRoutes(sugar, sqlxDB)
	srv := &http.Server{
		Addr:    "0.0.0.0:8431",
		Handler: handler,
	}

	// run server in background
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			sugar.Fatalf("http server failed: %v", err)
		}
	}()

	<-ctx.Done()

	sugar.Info("shutting down")

	// give a short grace period for cleanup
	doneCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// ping db once more
	if err := sqlDB.PingContext(doneCtx); err != nil {
		sugar.Warnf("db ping on shutdown failed: %v", err)
	}

	// shutdown http server
	if err := srv.Shutdown(doneCtx); err != nil {
		sugar.Warnf("http server shutdown failed: %v", err)
	}

	sugar.Info("goodbye")
}
