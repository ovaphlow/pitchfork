package user

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/jmoiron/sqlx"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user/entity"
	userrepo "github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user/repo"
)

// PasswordHasher defines minimal hashing interface (abstract so we can swap to argon2 later).
type PasswordHasher interface {
	Hash(pw string) (hash string, algo string, err error)
	Verify(hash, pw string) bool
	NeedsRehash(hash string) bool
}

// BcryptHasher implementation.
type BcryptHasher struct{ Cost int }

func (b BcryptHasher) Hash(pw string) (string, string, error) {
	cost := b.Cost
	if cost == 0 {
		cost = bcrypt.DefaultCost
	}
	h, err := bcrypt.GenerateFromPassword([]byte(pw), cost)
	if err != nil {
		return "", "", err
	}
	return string(h), fmt.Sprintf("bcrypt:%d", cost), nil
}
func (b BcryptHasher) Verify(hash, pw string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(pw)) == nil
}
func (b BcryptHasher) NeedsRehash(hash string) bool {
	// naive: parse cost and compare with current desired
	parts := strings.Split(hash, "$")
	for i, p := range parts {
		if p == "2a" || p == "2b" || p == "2y" { // next part should contain cost
			if i+2 < len(parts) { // format: $2b$10$...
				costField := parts[i+1]
				if len(costField) >= 2 { // e.g. 10
					// skip strict parse for brevity
				}
			}
			break
		}
	}
	return false
}

// UserService orchestrates authentication and user lifecycle flows.
type UserService struct {
	repo   *userrepo.UserRepo
	hasher PasswordHasher
	// configuration knobs
	MaxFailed   int
	LockMinutes int
}

func NewUserService(db *sqlx.DB, r *userrepo.UserRepo, hasher PasswordHasher) *UserService {
	if r == nil {
		r = userrepo.NewUserRepo(db)
	}
	if hasher == nil {
		hasher = BcryptHasher{Cost: 12}
	}
	return &UserService{repo: r, hasher: hasher, MaxFailed: 6, LockMinutes: 15}
}

var (
	ErrUserNotFound      = errors.New("user not found")
	ErrLocked            = errors.New("user locked")
	ErrDisabled          = errors.New("user disabled")
	ErrBadCredentials    = errors.New("invalid credentials")
	ErrMustResetPassword = errors.New("must reset password")
)

// AuthenticatePassword performs password authentication by email or username (one must be non-empty).
// On success resets counters and returns the user minimal auth view.
func (s *UserService) AuthenticatePassword(ctx context.Context, identifier, password string) (*entity.MinimalAuthView, error) {
	identifier = strings.TrimSpace(identifier)
	if identifier == "" {
		return nil, ErrBadCredentials
	}

	// Try email first if contains '@'
	var u *entity.User
	var err error
	if strings.Contains(identifier, "@") {
		u, err = s.repo.GetByEmail(ctx, identifier)
	} else {
		u, err = s.repo.GetByUsername(ctx, identifier)
	}
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrBadCredentials
		} // avoid user enumeration
		return nil, err
	}

	// Expired lock auto-unlock attempt
	if u.Status == "locked" && u.LockedUntil != nil && u.LockedUntil.Before(time.Now()) {
		if unlocked, _ := s.repo.UnlockIfExpired(ctx, u.ID); unlocked {
			u.Status = "active"
			u.LockedUntil = nil
		}
	}

	if u.Status == "locked" {
		return nil, ErrLocked
	}
	if u.Status == "disabled" {
		return nil, ErrDisabled
	}
	if u.PasswordHash == nil || *u.PasswordHash == "" {
		return nil, ErrBadCredentials
	}

	if !s.hasher.Verify(*u.PasswordHash, password) {
		// failure path
		if _, incErr := s.repo.IncrementFailedLogin(ctx, u.ID); incErr == nil {
			// attempt lock
			_, _ = s.repo.LockIfThreshold(ctx, u.ID, s.MaxFailed, s.LockMinutes)
		}
		return nil, ErrBadCredentials
	}

	// success path
	if err := s.repo.ResetLoginSuccess(ctx, u.ID); err != nil {
		return nil, err
	}

	if u.MustResetPassword {
		return nil, ErrMustResetPassword
	}

	view, err := s.repo.GetMinimalAuthView(ctx, u.ID)
	if err != nil {
		return nil, err
	}

	// Optionally rehash if algorithm outdated (placeholder always false now)
	if u.PasswordAlgo != nil && s.hasher.NeedsRehash(*u.PasswordHash) {
		if newHash, algo, hErr := s.hasher.Hash(password); hErr == nil {
			_ = s.repo.UpdatePassword(ctx, u.ID, newHash, algo, false)
		}
	}
	return view, nil
}

// SignupUser creates a user with password (hashing inside). Minimal required: username OR email, password.
func (s *UserService) SignupUser(ctx context.Context, username, email, password, userType string, mustReset bool) (int64, error) {
	if username == "" && email == "" {
		return 0, errors.New("username or email required")
	}
	var normalizedEmail *string
	if email != "" {
		e := strings.ToLower(strings.TrimSpace(email))
		normalizedEmail = &e
	}
	var uname *string
	if username != "" {
		u := strings.TrimSpace(username)
		uname = &u
	}
	hash, algo, err := s.hasher.Hash(password)
	if err != nil {
		return 0, err
	}
	status := "active"
	u := &entity.User{
		Username:          uname,
		Email:             normalizedEmail,
		EmailVerified:     false,
		PasswordHash:      &hash,
		PasswordAlgo:      &algo,
		MustResetPassword: mustReset,
		Status:            status,
		UserType:          &userType,
		Version:           1,
	}
	return s.repo.Create(ctx, u)
}

// BumpVersionAndRevoke coordinates version bump and returns new version (revoke logic for refresh tokens left to caller).
func (s *UserService) BumpVersionAndRevoke(ctx context.Context, userID int64) (int64, error) {
	if err := s.repo.BumpVersion(ctx, userID); err != nil {
		return 0, err
	}
	v, err := s.repo.GetMinimalAuthView(ctx, userID)
	if err != nil {
		return 0, err
	}
	return v.Version, nil
}

// GetMinimalAuthView retrieves the minimal projection for a user by ID.
func (s *UserService) GetMinimalAuthView(ctx context.Context, id int64) (*entity.MinimalAuthView, error) {
	return s.repo.GetMinimalAuthView(ctx, id)
}

// ConstantTimeCompare helper (exposed if later we store API keys etc.)
func ConstantTimeCompare(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
