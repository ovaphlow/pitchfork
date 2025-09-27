package setting

import "github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting/repo"

// Service encapsulates business logic for settings and depends on a repo.
type Service struct {
	repo *repo.Repo
}

// NewService constructs a Service with the provided repository.
func NewService(r *repo.Repo) *Service {
	return &Service{repo: r}
}

// TODO: add business methods on Service, e.g. List, Get, Create, etc.
