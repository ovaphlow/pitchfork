package main

import (
    "context"
    "fmt"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/db"
    "github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/logger"
)

func main() {
    // init logger
    lg, err := logger.Init(logger.ConfigFromEnv())
    if err != nil {
        fmt.Fprintf(os.Stderr, "failed to init logger: %v\n", err)
        os.Exit(1)
    }
    defer lg.Sync()

    sugar := lg.Sugar()
    sugar.Info("starting service-core-go-stdlib")

    // init db
    cfg := db.ConfigFromEnv()
    sqlDB, err := db.Connect(cfg)
    if err != nil {
        sugar.Fatalf("db connect: %v", err)
    }
    defer sqlDB.Close()

    // graceful shutdown
    ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
    defer stop()

    sugar.Info("service is running; press Ctrl+C to stop")

    <-ctx.Done()

    sugar.Info("shutting down")

    // give a short grace period for cleanup
    doneCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    // ping db once more
    if err := sqlDB.PingContext(doneCtx); err != nil {
        sugar.Warnf("db ping on shutdown failed: %v", err)
    }

    sugar.Info("goodbye")
}

