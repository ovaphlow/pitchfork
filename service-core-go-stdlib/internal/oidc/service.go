package oidc

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"
	repo "github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/oidc/repo"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user/entity"
)

// OIDCService manages signing keys and token issuance.
type OIDCService struct {
	key    *rsa.PrivateKey
	kid    string
	issuer string
	// DB-backed refresh repository
	refreshRepo *repo.RefreshRepo
}

func NewOIDCService(db *sqlx.DB, issuer string) (*OIDCService, error) {
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	// generate simple kid as base64 of SHA256 of public key
	pubBytes, _ := json.Marshal(k.PublicKey)
	h := sha256.Sum256(pubBytes)
	kid := base64.RawURLEncoding.EncodeToString(h[:8])
	r := repo.NewRefreshRepo(db)
	return &OIDCService{key: k, kid: kid, issuer: issuer, refreshRepo: r}, nil
}

// JWKS returns a minimal JWKS containing the public key.
func (s *OIDCService) JWKS() (map[string]any, error) {
	pub := s.key.PublicKey
	n := base64.RawURLEncoding.EncodeToString(pub.N.Bytes())
	// encode exponent using big.Int to get minimal big-endian bytes
	e := base64.RawURLEncoding.EncodeToString(new(big.Int).SetInt64(int64(pub.E)).Bytes())
	jwk := map[string]any{
		"kty": "RSA",
		"use": "sig",
		"alg": "RS256",
		"kid": s.kid,
		"n":   n,
		"e":   e,
	}
	return map[string]any{"keys": []any{jwk}}, nil
}

// PublicKey returns the RSA public key for verification.
func (s *OIDCService) PublicKey() *rsa.PublicKey {
	return &s.key.PublicKey
}

func bigIntToBytes(i int) []byte {
	// retained for backward-compat if called elsewhere; convert via big.Int
	return new(big.Int).SetInt64(int64(i)).Bytes()
}

// IssueTokens creates an id_token and access_token for the given user.
func (s *OIDCService) IssueTokens(ctx context.Context, u *entity.MinimalAuthView, audience string, ttl time.Duration) (idToken string, accessToken string, refreshToken string, err error) {
	now := time.Now()
	// ID Token
	idClaims := jwt.MapClaims{
		"iss":            s.issuer,
		"sub":            fmt.Sprintf("%d", u.ID),
		"aud":            audience,
		"exp":            now.Add(ttl).Unix(),
		"iat":            now.Unix(),
		"v":              u.Version,
		"user_type":      u.UserType,
		"email":          u.Email,
		"email_verified": u.EmailVerified,
	}
	idTok := jwt.NewWithClaims(jwt.SigningMethodRS256, idClaims)
	idTok.Header["kid"] = s.kid
	signedID, err := idTok.SignedString(s.key)
	if err != nil {
		return "", "", "", err
	}

	// Access token (shorter lived)
	accessClaims := jwt.MapClaims{
		"iss":       s.issuer,
		"sub":       fmt.Sprintf("%d", u.ID),
		"aud":       audience,
		"exp":       now.Add(ttl).Unix(),
		"iat":       now.Unix(),
		"v":         u.Version,
		"user_type": u.UserType,
	}
	access := jwt.NewWithClaims(jwt.SigningMethodRS256, accessClaims)
	access.Header["kid"] = s.kid
	signedAccess, err := access.SignedString(s.key)
	if err != nil {
		return "", "", "", err
	}

	// create a simple opaque refresh token and persist session in DB
	rtBytes := make([]byte, 32)
	if _, err := rand.Read(rtBytes); err != nil {
		return "", "", "", err
	}
	refresh := base64.RawURLEncoding.EncodeToString(rtBytes)
	rs := RefreshSession{
		UserID:    u.ID,
		ClientID:  audience,
		ExpiresAt: now.Add(30 * 24 * time.Hour),
	}
	id, err := s.refreshRepo.Save(ctx, refresh, rs.UserID, rs.ClientID, rs.ExpiresAt)
	if err != nil {
		return "", "", "", err
	}
	rs.ID = id

	return signedID, signedAccess, refresh, nil
}

// ValidateRefreshToken checks an opaque refresh token and returns the session if valid.
func (s *OIDCService) ValidateRefreshToken(ctx context.Context, token string) (*RefreshSession, bool) {
	id, userID, clientID, expiresAt, err := s.refreshRepo.Get(ctx, token)
	if err != nil {
		return nil, false
	}
	rs := RefreshSession{ID: id, UserID: userID, ClientID: clientID, ExpiresAt: expiresAt}
	if rs.ExpiresAt.Before(time.Now()) {
		return nil, false
	}
	return &rs, true
}

// RevokeRefreshToken removes a refresh token from store.
func (s *OIDCService) RevokeRefreshToken(ctx context.Context, token string) error {
	return s.refreshRepo.Delete(ctx, token)
}
