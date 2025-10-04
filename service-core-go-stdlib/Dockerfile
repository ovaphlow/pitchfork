# syntax=docker/dockerfile:1.7
# Multi-stage build for service-core-go-stdlib
# Stage 1: build
FROM docker.1ms.run/library/golang:1.25-alpine AS build

ENV CGO_ENABLED=0 GOOS=linux GOARCH=amd64
# Use China Go module proxy and sumdb to speed up module downloads inside China
ENV GOPROXY=https://goproxy.cn,direct \
    GOSUMDB=sum.golang.google.cn
WORKDIR /app

# Install build deps (if any future need)
RUN sed -i 's|dl-cdn.alpinelinux.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apk/repositories \
    && apk add --no-cache git ca-certificates && update-ca-certificates

# Go module files first for better layer caching
COPY go.mod go.sum ./
RUN go mod download

# Copy the rest of the source
COPY . .

# Build the binary
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    go build -trimpath -ldflags="-s -w" -o /out/service-core ./cmd/api

# Stage 2: minimal runtime (distroless static)
# FROM gcr.io/distroless/static:nonroot AS runtime
FROM gcr.nju.edu.cn/distroless/static:nonroot AS runtime
WORKDIR /app

# Copy binary
COPY --from=build /out/service-core /app/service-core

# Expose internal port noted in main.go
EXPOSE 8431

# Non-root user from distroless static base is numeric 65532
USER nonroot:nonroot

ENV GIN_MODE=release \
    TZ=UTC

ENTRYPOINT ["/app/service-core"]
