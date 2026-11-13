package httpapi

import (
	"errors"
	"io"
	"net/http"
	"path"
	"strings"
	"unicode/utf8"
)

const (
	// maxUploadBytes bounds one uploaded file; maxUploadRequestBytes adds room
	// for the multipart envelope so the file limit is what users actually hit.
	maxUploadBytes        = 8 << 20
	maxUploadRequestBytes = maxUploadBytes + (1 << 20)
	maxDownloadBytes      = 16 << 20
)

// uploadTypes are the document types accepted as review evidence.
var uploadTypes = map[string]string{
	"image/jpeg":      ".jpg",
	"image/png":       ".png",
	"image/webp":      ".webp",
	"application/pdf": ".pdf",
}

type uploadedFile struct {
	ID         string `json:"id"`
	ContentURL string `json:"content_url"`
	MimeType   string `json:"mime_type"`
	SizeBytes  int64  `json:"size_bytes"`
}

// uploadFile proxies one multipart file to Nexus. Nexus owns storage and the
// file id; agora-api only relays the Cookie and reports the new id, which the
// caller then stores in organisations.metadata.license_file_id.
func (deps Deps) uploadFile(responseWriter http.ResponseWriter, request *http.Request) {
	if _, err := deps.authenticate(request); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if deps.Files == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "文件服务暂不可用")
		return
	}

	request.Body = http.MaxBytesReader(responseWriter, request.Body, maxUploadRequestBytes)
	if err := request.ParseMultipartForm(maxUploadBytes); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(responseWriter, http.StatusRequestEntityTooLarge, "文件不能超过 8 MB")
			return
		}
		writeError(responseWriter, http.StatusBadRequest, "文件上传失败")
		return
	}
	defer func() {
		if request.MultipartForm != nil {
			_ = request.MultipartForm.RemoveAll()
		}
	}()

	file, header, err := request.FormFile("file")
	if err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请选择要上传的文件")
		return
	}
	defer file.Close()

	content, err := io.ReadAll(io.LimitReader(file, maxUploadBytes+1))
	if err != nil {
		writeError(responseWriter, http.StatusBadRequest, "文件读取失败")
		return
	}
	if len(content) == 0 {
		writeError(responseWriter, http.StatusBadRequest, "文件内容为空")
		return
	}
	if len(content) > maxUploadBytes {
		writeError(responseWriter, http.StatusRequestEntityTooLarge, "文件不能超过 8 MB")
		return
	}

	// Trust the bytes, not the client-supplied header.
	contentType := http.DetectContentType(content)
	if _, ok := uploadTypes[contentType]; !ok {
		writeError(responseWriter, http.StatusBadRequest, "仅支持 JPG、PNG、WebP 或 PDF")
		return
	}

	stored, err := deps.Files.UploadFile(
		request.Context(),
		request.Header.Get("Cookie"),
		uploadName(header.Filename, contentType),
		contentType,
		content,
	)
	if err != nil {
		deps.Logger.Warn("file upload failed", "request_id", requestIDFrom(request.Context()), "error", err)
		writeError(responseWriter, http.StatusBadGateway, "文件服务暂不可用")
		return
	}

	writeJSON(responseWriter, http.StatusCreated, uploadedFile{
		ID:         stored.ID,
		ContentURL: "/crate-api/agora/v1/files/" + stored.ID + "/content",
		MimeType:   contentType,
		SizeBytes:  int64(len(content)),
	})
}

// getFile proxies the stored file back to an authenticated caller.
func (deps Deps) getFile(responseWriter http.ResponseWriter, request *http.Request) {
	if _, err := deps.authenticate(request); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if deps.Files == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "文件服务暂不可用")
		return
	}

	fileID := strings.TrimSpace(request.PathValue("id"))
	if !validFileID(fileID) {
		writeError(responseWriter, http.StatusBadRequest, "文件标识无效")
		return
	}

	response, err := deps.Files.FileContent(request.Context(), request.Header.Get("Cookie"), fileID)
	if err != nil {
		deps.Logger.Warn("file download failed", "request_id", requestIDFrom(request.Context()), "error", err)
		writeError(responseWriter, http.StatusBadGateway, "文件服务暂不可用")
		return
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		writeError(responseWriter, http.StatusNotFound, "文件不存在")
		return
	}

	contentType := response.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	if _, ok := uploadTypes[contentType]; !ok {
		contentType = "application/octet-stream"
	}

	responseWriter.Header().Set("Content-Type", contentType)
	responseWriter.Header().Set("Content-Disposition", "inline")
	responseWriter.Header().Set("Cache-Control", "private, max-age=300")
	responseWriter.Header().Set("X-Content-Type-Options", "nosniff")
	responseWriter.WriteHeader(http.StatusOK)
	_, _ = io.Copy(responseWriter, io.LimitReader(response.Body, maxDownloadBytes))
}

// uploadName keeps the original name for auditors but never trusts it as a path.
func uploadName(raw, contentType string) string {
	name := path.Base(strings.TrimSpace(raw))
	if name == "" || name == "." || name == "/" {
		name = "license" + uploadTypes[contentType]
	}
	if utf8.RuneCountInString(name) > 120 {
		runes := []rune(name)
		name = string(runes[len(runes)-120:])
	}
	return name
}

// validFileID accepts the 26-character Crockford Base32 ULID Nexus issues.
func validFileID(value string) bool {
	if len(value) != 26 {
		return false
	}
	for _, r := range value {
		switch {
		case r >= '0' && r <= '9', r >= 'A' && r <= 'Z':
		default:
			return false
		}
	}
	return true
}
