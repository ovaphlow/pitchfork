package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/config"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/httpapi"
	"github.com/ovaphlow/pitchfork/platform/internal/identity"
	"github.com/ovaphlow/pitchfork/platform/internal/logging"
	"github.com/ovaphlow/pitchfork/platform/internal/mail"
)

func main() {
	logger, closeLogs, err := logging.New("logs", os.Stdout)
	if err != nil {
		slog.New(slog.NewTextHandler(os.Stderr, nil)).Error("initialize logging", "error", err)
		os.Exit(1)
	}
	defer func() {
		if err := closeLogs(); err != nil {
			fmt.Fprintf(os.Stderr, "close logs: %v\n", err)
		}
	}()

	configuration, err := config.Load()
	if err != nil {
		logger.Error("load configuration", "error", err)
		os.Exit(1)
	}

	// 共享模块（055）：文件实体存放在 PLATFORMD_FILES_DIR。
	if err := os.MkdirAll(configuration.FilesDir, 0o750); err != nil {
		logger.Error("create shared file storage", "error", err)
		os.Exit(1)
	}

	runContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	databaseConnection, err := database.OpenSQLite(runContext, configuration.DatabasePath)
	if err != nil {
		logger.Error("open database", "error", err)
		os.Exit(1)
	}
	defer databaseConnection.Close()

	migrationResult, err := database.Migrate(runContext, databaseConnection, migrations.Files)
	if err != nil {
		logger.Error("migrate database", "error", err)
		os.Exit(1)
	}
	logger.Info("database migrations complete", "applied", migrationResult.Applied)

	bootstrapCreated, err := identity.EnsureBootstrap(runContext, databaseConnection, identity.BootstrapInput{
		Identifier: configuration.BootstrapIdentifier,
		Password:   configuration.BootstrapPassword,
	})
	if err != nil {
		logger.Error("ensure bootstrap administrator", "error", err)
		os.Exit(1)
	}
	if bootstrapCreated {
		logger.Info("bootstrap platform administrator ensured")
	}

	// 邮件链接登录只有在配置了 SMTP 中继时才启用；未配置时端点返回 503。
	var emailLoginLinkMailer identity.EmailLoginLinkMailer
	if configuration.SMTPAddress != "" {
		smtpMailer, err := mail.NewSMTPMailer(mail.Config{
			Address:  configuration.SMTPAddress,
			From:     configuration.SMTPFrom,
			Username: configuration.SMTPUsername,
			Password: configuration.SMTPPassword,
			TLSMode:  configuration.SMTPTLSMode,
			Timeout:  configuration.SMTPTimeout,
		})
		if err != nil {
			logger.Error("configure SMTP", "error", err)
			os.Exit(1)
		}
		emailLoginLinkMailer = smtpMailer
	}

	server := &http.Server{
		Addr: configuration.Address,
		Handler: httpapi.NewMux(databaseConnection, httpapi.Options{
			SessionSettings: identity.SessionSettings{TTL: configuration.SessionTTL, IdleTTL: configuration.SessionIdleTTL},
			LoginThrottle: identity.LoginThrottleSettings{
				Secret:          configuration.LoginThrottleSecret,
				FailureLimit:    configuration.LoginThrottleFailureLimit,
				Window:          configuration.LoginThrottleWindow,
				LockoutDuration: configuration.LoginThrottleLockout,
			},
			SecureSessionCookie:  configuration.SecureSessionCookie,
			TrustedProxyPrefixes: configuration.TrustedProxyPrefixes,
			CorsOrigins:          configuration.CorsOrigins,
			SharedFilesDir:       configuration.FilesDir,
			SharedMaxUploadBytes: configuration.MaxUploadBytes,
			EmailLoginLinkSettings: identity.EmailLoginLinkSettings{
				TTL:          configuration.EmailLoginLinkTTL,
				RedirectURIs: configuration.EmailLoginLinkRedirectURIs,
				RateWindow:   configuration.EmailLoginLinkRateWindow,
				EmailLimit:   configuration.EmailLoginLinkEmailLimit,
				SourceLimit:  configuration.EmailLoginLinkSourceLimit,
			},
			EmailLoginLinkMailer: emailLoginLinkMailer,
			Logger:               logger,
		}),
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
			os.Exit(1)
		}
	case <-runContext.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			logger.Error("shutdown HTTP", "error", err)
			os.Exit(1)
		}
	}
}
