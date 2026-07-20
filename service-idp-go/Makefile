GO ?= go
SQLC ?= $(shell $(GO) env GOPATH)/bin/sqlc

.PHONY: assets build generate run test vet

assets:

build: generate
	mkdir -p bin
	$(GO) build -o bin/identityd ./cmd/identityd

generate:
	rm -rf internal/database/sqlc
	$(SQLC) generate

run: generate
	$(GO) run ./cmd/identityd

test: generate
	$(GO) test ./...

vet: generate
	$(GO) vet ./...
