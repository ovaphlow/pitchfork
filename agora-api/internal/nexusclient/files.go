package nexusclient

import (
	"bytes"
	"context"
	"fmt"
	"mime"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"strings"
)

// filesPath is the platformd shared files collection. Uploads and downloads require a
// valid IDP session, so the caller forwards the browser Cookie header untouched.
const filesPath = "/crate-api/v1/files"

// File is the metadata platformd returns after a successful upload.
type File struct {
	ID           string `json:"id"`
	OriginalName string `json:"original_name"`
	MimeType     string `json:"mime_type"`
	SizeBytes    int64  `json:"size_bytes"`
}

// UploadFile sends one image/PDF to the platformd shared files API and returns its metadata.
func (c *Client) UploadFile(
	ctx context.Context,
	cookieHeader, filename, contentType string,
	content []byte,
) (File, error) {
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)

	disposition := mime.FormatMediaType("form-data", map[string]string{
		"name":     "file",
		"filename": filename,
	})
	part, err := writer.CreatePart(textprotoHeader(disposition, contentType))
	if err != nil {
		return File{}, fmt.Errorf("build upload form: %w", err)
	}
	if _, err := part.Write(content); err != nil {
		return File{}, fmt.Errorf("write upload form: %w", err)
	}
	if err := writer.Close(); err != nil {
		return File{}, fmt.Errorf("close upload form: %w", err)
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+filesPath+"/upload", &body)
	if err != nil {
		return File{}, fmt.Errorf("build upload request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("X-App-Code", appCode)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	if cookie := strings.TrimSpace(cookieHeader); cookie != "" {
		request.Header.Set("Cookie", cookie)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return File{}, fmt.Errorf("call upload endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusCreated && response.StatusCode != http.StatusOK {
		return File{}, fmt.Errorf("upload endpoint returned HTTP %d", response.StatusCode)
	}

	var file File
	if err := jsonDecode(response.Body, &file); err != nil {
		return File{}, fmt.Errorf("decode upload response: %w", err)
	}
	if file.ID == "" {
		return File{}, fmt.Errorf("upload response is missing id")
	}
	return file, nil
}

func textprotoHeader(disposition, contentType string) textproto.MIMEHeader {
	header := textproto.MIMEHeader{}
	header.Set("Content-Disposition", disposition)
	if contentType != "" {
		header.Set("Content-Type", contentType)
	}
	return header
}

// FileContent returns the raw upstream response for one stored file. The caller
// owns closing the body and must relay the status code.
func (c *Client) FileContent(ctx context.Context, cookieHeader, fileID string) (*http.Response, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		c.baseURL+filesPath+"/"+url.PathEscape(fileID)+"/content",
		nil,
	)
	if err != nil {
		return nil, fmt.Errorf("build file content request: %w", err)
	}
	request.Header.Set("Accept", "*/*")
	request.Header.Set("X-App-Code", appCode)
	if cookie := strings.TrimSpace(cookieHeader); cookie != "" {
		request.Header.Set("Cookie", cookie)
	}
	return c.http.Do(request)
}
