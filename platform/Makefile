GO ?= go
NODE ?= node
PNPM ?= pnpm

.PHONY: assets build run test vet

assets:
	$(PNPM) install --frozen-lockfile
	$(NODE) web/scripts/build-assets.mjs

build: assets
	mkdir -p bin
	$(GO) build -o bin/platformd ./cmd/platformd

run: assets
	$(GO) run ./cmd/platformd

test:
	$(GO) test ./...

vet:
	$(GO) vet ./...
