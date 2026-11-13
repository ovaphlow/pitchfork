// Package nexusclient reads identity-backed base data from service-nexus-shared.
// Nexus authorizes requests by forwarding the same opaque IDP cookie, so the
// caller passes the browser Cookie header through unchanged.
package nexusclient

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const permissionsPath = "/crate-api/v1/subject-permissions"

// Permissions is the resolved role and permission set for one subject.
type Permissions struct {
	RoleCodes       []string `json:"role_codes"`
	PermissionCodes []string `json:"permission_codes"`
}

// Client is a small HTTP client for the base-data service.
type Client struct {
	baseURL string
	http    *http.Client
}

func New(baseURL string, timeout time.Duration) *Client {
	return &Client{
		baseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"),
		http:    &http.Client{Timeout: timeout},
	}
}

// SubjectPermissions reads the subject's own roles and permission codes.
func (c *Client) SubjectPermissions(ctx context.Context, cookieHeader, subjectID string) (Permissions, error) {
	query := url.Values{"subject_id": {subjectID}}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+permissionsPath+"?"+query.Encode(), nil)
	if err != nil {
		return Permissions{}, fmt.Errorf("build permissions request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	if cookie := strings.TrimSpace(cookieHeader); cookie != "" {
		request.Header.Set("Cookie", cookie)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return Permissions{}, fmt.Errorf("call permissions endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return Permissions{}, fmt.Errorf("permissions endpoint returned HTTP %d", response.StatusCode)
	}

	var permissions Permissions
	if err := jsonDecode(response.Body, &permissions); err != nil {
		return Permissions{}, fmt.Errorf("decode permissions response: %w", err)
	}
	if permissions.RoleCodes == nil {
		permissions.RoleCodes = []string{}
	}
	if permissions.PermissionCodes == nil {
		permissions.PermissionCodes = []string{}
	}
	return permissions, nil
}

func jsonDecode(reader io.Reader, destination any) error {
	return json.NewDecoder(io.LimitReader(reader, 1<<20)).Decode(destination)
}
