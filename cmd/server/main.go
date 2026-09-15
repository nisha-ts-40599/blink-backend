package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/nisha-ts-40599/blink-backend/internal/agent"
	"github.com/nisha-ts-40599/blink-backend/internal/auth"
	"github.com/nisha-ts-40599/blink-backend/internal/config"
	"github.com/nisha-ts-40599/blink-backend/internal/crypto"
	"github.com/nisha-ts-40599/blink-backend/internal/db"
	"github.com/nisha-ts-40599/blink-backend/internal/httpapi"
	"github.com/nisha-ts-40599/blink-backend/internal/integrations"
	"github.com/nisha-ts-40599/blink-backend/internal/mailer"
	"github.com/nisha-ts-40599/blink-backend/internal/project"
	"github.com/nisha-ts-40599/blink-backend/internal/s3ws"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	ctx := context.Background()
	pool, err := db.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer pool.Close()

	if err := db.Migrate(ctx, pool); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	secret := strings.TrimSpace(cfg.IntegrationSecretKey)
	if secret == "" {
		secret = "blink-local-dev-only-change-me"
		log.Printf("warning: BLINK_INTEGRATION_SECRET_KEY unset; using local default")
	}
	box, err := crypto.New(secret)
	if err != nil {
		log.Fatalf("crypto: %v", err)
	}

	mail := mailer.New(cfg)
	authSvc := auth.New(pool, cfg, mail)
	proj := project.New(pool)
	agentClient := agent.New(cfg)
	integ := integrations.New(pool, cfg, box)
	s3svc := s3ws.New(cfg)
	handler := httpapi.New(cfg, authSvc, proj, agentClient, mail, integ, s3svc)

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       90 * time.Second,
	}

	go func() {
		log.Printf("blink-backend (go) listening on :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}
