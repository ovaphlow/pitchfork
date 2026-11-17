package identityclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

// Subject is the platformd administration view of one account.
type Subject struct {
	ID            string `json:"id"`
	Status        string `json:"status"`
	DisplayName   string `json:"display_name"`
	Identifier    string `json:"identifier"`
	PlatformAdmin bool   `json:"platform_admin"`
	CreatedAt     string `json:"created_at"`
	UpdatedAt     string `json:"updated_at"`
}

// SubjectPage is the platformd list contract.
type SubjectPage struct {
	Records []Subject `json:"records"`
	Meta    struct {
		Total int64 `json:"total"`
	} `json:"meta"`
}

// ListSubjectsByIDs fetches the requested accounts in one call. platformd returns
// at most 100 records; the caller decides their display order.
func (c *Client) ListSubjectsByIDs(ctx context.Context, cookieHeader string, subjectIDs []string) ([]Subject, error) {
	if len(subjectIDs) == 0 {
		return []Subject{}, nil
	}
	query := url.Values{"subject_ids": {strings.Join(subjectIDs, ",")}}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.baseURL+identityPrefix+"/subjects?"+query.Encode(), nil)
	if err != nil {
		return nil, fmt.Errorf("build subjects request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cookie", cookieHeader)

	response, err := c.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("call subjects endpoint: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, upstreamError(response)
	}
	var page SubjectPage
	if err := decodeJSON(response.Body, &page); err != nil {
		return nil, fmt.Errorf("decode subjects response: %w", err)
	}
	if page.Records == nil {
		page.Records = []Subject{}
	}
	return page.Records, nil
}

// UpdateSubjectStatus enables or disables one platform account. platformd requires
// the CSRF token that accompanies the session cookie.
func (c *Client) UpdateSubjectStatus(ctx context.Context, cookieHeader, csrfToken, subjectID, status string) (Subject, error) {
	body, err := json.Marshal(map[string]string{"status": status})
	if err != nil {
		return Subject{}, fmt.Errorf("encode subject update: %w", err)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPatch,
		c.baseURL+identityPrefix+"/subjects/"+url.PathEscape(subjectID), bytes.NewReader(body))
	if err != nil {
		return Subject{}, fmt.Errorf("build subject update request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Cookie", cookieHeader)
	if strings.TrimSpace(csrfToken) == "" {
		csrfToken = cookieValue(cookieHeader, CSRFCookieName)
	}
	if csrfToken != "" {
		request.Header.Set("X-CSRF-Token", csrfToken)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return Subject{}, fmt.Errorf("call subject update endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return Subject{}, upstreamError(response)
	}
	var subject Subject
	if err := decodeJSON(response.Body, &subject); err != nil {
		return Subject{}, fmt.Errorf("decode subject update response: %w", err)
	}
	if subject.ID == "" {
		return Subject{}, fmt.Errorf("subject update response is missing id")
	}
	return subject, nil
}
