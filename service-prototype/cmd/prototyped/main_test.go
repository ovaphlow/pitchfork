package main

import (
	"bytes"
	"context"
	"strings"
	"testing"
)

func valuesLookup(values map[string]string) func(string) string {
	return func(key string) string {
		return values[key]
	}
}

// canceledContext returns a context that is already done, so run() reaches
// the shutdown path immediately after startup.
func canceledContext() (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx, cancel
}

func TestRunExitsOneOnConfigurationError(t *testing.T) {
	var stderr bytes.Buffer
	code := run(context.Background(), valuesLookup(map[string]string{"PORT": "not-a-number"}), &bytes.Buffer{}, &stderr)
	if code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
	if !strings.Contains(stderr.String(), "load configuration") {
		t.Fatalf("stderr %q does not report the configuration error", stderr.String())
	}
}

func TestRunStartsHTTPServerAndShutsDownGracefully(t *testing.T) {
	ctx, cancel := canceledContext()
	defer cancel()
	var stdout bytes.Buffer
	code := run(ctx, valuesLookup(map[string]string{"PORT": "0"}), &stdout, &bytes.Buffer{})
	if code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if !strings.Contains(stdout.String(), "starting prototyped") {
		t.Fatalf("stdout %q does not record the server start", stdout.String())
	}
}
