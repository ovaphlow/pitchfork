package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/nexusclient"
)

const testFileID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

// onePixelPNG is a valid 1x1 PNG so http.DetectContentType sees image/png.
var onePixelPNG = func() []byte {
	decoded, err := base64.StdEncoding.DecodeString("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==")
	if err != nil {
		panic(err)
	}
	return decoded
}()

type nexusUpload struct {
	filename    string
	contentType string
	content     []byte
}

// newFilesDeps wires the HTTP layer to a fake Nexus files service and the same
// fake platformd used by the auth tests.
func newFilesDeps(t *testing.T, uploads *[]nexusUpload) http.Handler {
	t.Helper()

	nexus := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.Header.Get("Cookie"), identityclient.SessionCookieName+"=session-token") {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/crate-api/v1/files/upload":
			file, header, err := r.FormFile("file")
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			defer file.Close()
			content, err := io.ReadAll(file)
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			*uploads = append(*uploads, nexusUpload{
				filename:    header.Filename,
				contentType: header.Header.Get("Content-Type"),
				content:     content,
			})
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id":            testFileID,
				"original_name": header.Filename,
				"mime_type":     header.Header.Get("Content-Type"),
				"size_bytes":    len(content),
			})
		case r.Method == http.MethodGet && r.URL.Path == "/crate-api/v1/files/"+testFileID+"/content":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(onePixelPNG)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(nexus.Close)

	identity := newSessionProvider(t, "SUB1", false)
	t.Cleanup(identity.Close)

	return New(Deps{
		Logger:           testLogger(),
		DB:               pingerFunc(func(ctx context.Context) error { return nil }),
		AllowedOrigins:   []string{"http://localhost:4321"},
		ReadinessTimeout: time.Second,
		Identity:         identityclient.New(identity.URL, time.Second),
		Files:            nexusclient.New(nexus.URL, 5*time.Second),
	})
}

func uploadRequest(t *testing.T, filename string, content []byte, signedIn bool) *http.Request {
	t.Helper()

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", filename)
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write(content); err != nil {
		t.Fatalf("write form file: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	request := httptest.NewRequest(http.MethodPost, "/crate-api/agora/v1/files", &body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	if signedIn {
		request.Header.Set("Cookie", identityclient.SessionCookieName+"=session-token")
	}
	return request
}

func TestUploadFileRequiresSession(t *testing.T) {
	var uploads []nexusUpload
	handler := newFilesDeps(t, &uploads)

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, uploadRequest(t, "license.png", onePixelPNG, false))

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
	if len(uploads) != 0 {
		t.Fatalf("anonymous upload reached Nexus: %v", uploads)
	}
}

func TestUploadFileRejectsUnsupportedType(t *testing.T) {
	var uploads []nexusUpload
	handler := newFilesDeps(t, &uploads)

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, uploadRequest(t, "notes.txt", []byte("not an image"), true))

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400, body = %s", response.Code, response.Body.String())
	}
	if len(uploads) != 0 {
		t.Fatalf("unsupported file reached Nexus: %v", uploads)
	}
}

func TestUploadFileForwardsImageToNexus(t *testing.T) {
	var uploads []nexusUpload
	handler := newFilesDeps(t, &uploads)

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, uploadRequest(t, "business-license.png", onePixelPNG, true))

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201, body = %s", response.Code, response.Body.String())
	}
	if len(uploads) != 1 {
		t.Fatalf("uploads = %d, want 1", len(uploads))
	}
	if uploads[0].filename != "business-license.png" {
		t.Fatalf("filename = %q", uploads[0].filename)
	}
	if uploads[0].contentType != "image/png" {
		t.Fatalf("content type = %q, want image/png", uploads[0].contentType)
	}
	if !bytes.Equal(uploads[0].content, onePixelPNG) {
		t.Fatal("uploaded bytes were altered")
	}

	var payload struct {
		ID         string `json:"id"`
		ContentURL string `json:"content_url"`
		MimeType   string `json:"mime_type"`
		SizeBytes  int64  `json:"size_bytes"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if payload.ID != testFileID {
		t.Fatalf("id = %q", payload.ID)
	}
	if payload.ContentURL != "/crate-api/agora/v1/files/"+testFileID+"/content" {
		t.Fatalf("content_url = %q", payload.ContentURL)
	}
	if payload.MimeType != "image/png" {
		t.Fatalf("mime_type = %q", payload.MimeType)
	}
	if payload.SizeBytes != int64(len(onePixelPNG)) {
		t.Fatalf("size_bytes = %d", payload.SizeBytes)
	}
}

func TestGetFileProxiesContent(t *testing.T) {
	var uploads []nexusUpload
	handler := newFilesDeps(t, &uploads)

	request := httptest.NewRequest(http.MethodGet, "/crate-api/agora/v1/files/"+testFileID+"/content", nil)
	request.Header.Set("Cookie", identityclient.SessionCookieName+"=session-token")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body = %s", response.Code, response.Body.String())
	}
	if got := response.Header().Get("Content-Type"); got != "image/png" {
		t.Fatalf("content type = %q", got)
	}
	if !bytes.Equal(response.Body.Bytes(), onePixelPNG) {
		t.Fatal("proxied bytes differ from the stored file")
	}
}

func TestGetFileRejectsInvalidID(t *testing.T) {
	var uploads []nexusUpload
	handler := newFilesDeps(t, &uploads)

	request := httptest.NewRequest(http.MethodGet, "/crate-api/agora/v1/files/..%2Fetc%2Fpasswd/content", nil)
	request.Header.Set("Cookie", identityclient.SessionCookieName+"=session-token")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest && response.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 400 or 404", response.Code)
	}
}

func TestUploadNameNeverEscapesTheBasename(t *testing.T) {
	if got := uploadName("../../etc/passwd", "image/png"); got != "passwd" {
		t.Fatalf("uploadName = %q", got)
	}
	if got := uploadName("", "image/jpeg"); got != "license.jpg" {
		t.Fatalf("uploadName fallback = %q", got)
	}
}
