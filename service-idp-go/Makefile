GO ?= go
SQLC ?= $(shell $(GO) env GOPATH)/bin/sqlc
NODE ?= node
PNPM ?= pnpm

.PHONY: assets build check-generated generate run test vet

assets:
	$(PNPM) install --frozen-lockfile
	$(NODE) web/scripts/build-assets.mjs

build: assets
	mkdir -p bin
	$(GO) build -o bin/identityd ./cmd/identityd

generate:
	rm -rf internal/database/sqlc
	$(SQLC) generate

check-generated: generate
	git diff --exit-code -- internal/database/sqlc
	@test -z "$$(git ls-files --others --exclude-standard -- internal/database/sqlc)" || { \
		echo "generated SQLC files are untracked"; \
		git status --short -- internal/database/sqlc; \
		exit 1; \
	}

run: assets
	$(GO) run ./cmd/identityd

test:
	$(GO) test ./...

vet:
	$(GO) vet ./...
