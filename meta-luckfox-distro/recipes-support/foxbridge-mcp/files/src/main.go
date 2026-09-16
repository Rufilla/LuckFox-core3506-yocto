// foxbridge-mcp — MCP server exposing safe hardware I/O to local agents.
//
// Binds 127.0.0.1 only. Expects to run as a dedicated system user that is a
// member of `gpio` and `leds` groups — the kernel enforces all real access
// control via file ownership on /dev/gpiochip* and /sys/class/leds/*.
package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:45521", "listen address (host:port)")
	flag.Parse()

	srv := newServer()

	mux := http.NewServeMux()
	mux.Handle("/mcp", srv)

	httpSrv := &http.Server{
		Addr:         *addr,
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	go func() {
		log.Printf("foxbridge-mcp listening on %s", *addr)
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()

	<-ctx.Done()
	log.Printf("shutting down")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		log.Printf("shutdown: %v", err)
		os.Exit(1)
	}
}
