GO ?= go

.PHONY: assets build run test vet

assets:
	@echo "service-prototype has no web assets yet; placeholder target."

build: assets
	mkdir -p bin
	$(GO) build -o bin/prototyped ./cmd/prototyped

run:
	$(GO) run ./cmd/prototyped

test:
	$(GO) test ./...

vet:
	$(GO) vet ./...
