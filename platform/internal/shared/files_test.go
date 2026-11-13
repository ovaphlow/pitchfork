package shared

// files_test 覆盖 platform/internal/shared/files.go 的实现细节：
// 契约形状（由 contract_files_test.go 黑盒复核）之外的存储、权限、路径安全、
// 分页/过滤、先盘后库删除、Content-Disposition 编码与错误映射。

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
)

const (
	filesTestSubjectID      = "01M3ZR3CDAQ6ZKHCYD065RSYMT"
	filesTestOtherSubjectID = "01KYE3F6CPNJS16RHT01M64D8B"
	filesTestInsertSQL      = "INSERT INTO files (id, original_name, stored_name, mime_type, size_bytes, storage_path, hash_sha256, uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
	filesTestProblemType    = "application/problem+json"
)

var filesTestULIDPattern = regexp.MustCompile("^[0-9A-HJKMNP-TV-Z]{26}$")

// filesTestHarness 用 httptest 直连 mux，不监听真实端口。
type filesTestHarness struct {
	t         *testing.T
	db        *sql.DB
	filesDir  string
	mux       *http.ServeMux
	sessionOK bool
}

func (harness *filesTestHarness) serve(request *http.Request) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	harness.mux.ServeHTTP(recorder, request)
	return recorder
}

func (harness *filesTestHarness) request(method, path string) *httptest.ResponseRecorder {
	harness.t.Helper()
	return harness.serve(httptest.NewRequest(method, path, nil))
}

func (harness *filesTestHarness) upload(parts ...filesTestPart) *httptest.ResponseRecorder {
	harness.t.Helper()
	body, contentType := filesTestMultipartBody(harness.t, parts...)
	request := httptest.NewRequest(http.MethodPost, Prefix+"/files/upload", bytes.NewReader(body))
	request.Header.Set("Content-Type", contentType)
	return harness.serve(request)
}

func (harness *filesTestHarness) uploadOne(fieldName, fileName, contentType string, content []byte) *httptest.ResponseRecorder {
	harness.t.Helper()
	return harness.upload(filesTestPart{FieldName: fieldName, FileName: fileName, ContentType: contentType, Content: content})
}

func (harness *filesTestHarness) insertFileMetadata(id, uploadedBy, createdAt, storedName, mimeType string) {
	harness.t.Helper()
	_, err := harness.db.ExecContext(harness.t.Context(), filesTestInsertSQL,
		id, "original-"+id, storedName, mimeType, int64(4), storedName, "hash-"+id, uploadedBy, createdAt)
	if err != nil {
		harness.t.Fatalf("insert file metadata %q: %v", id, err)
	}
}

func (harness *filesTestHarness) rowCount(query string, arguments ...any) int {
	harness.t.Helper()
	var count int
	if err := harness.db.QueryRowContext(harness.t.Context(), query, arguments...).Scan(&count); err != nil {
		harness.t.Fatalf("query row count: %v", err)
	}
	return count
}

func newFilesTestHarness(t *testing.T, maxUploadBytes int64) *filesTestHarness {
	t.Helper()

	harness := &filesTestHarness{
		t:         t,
		filesDir:  filepath.Join(t.TempDir(), "files"),
		sessionOK: true,
	}
	ctx := t.Context()
	db, err := database.OpenSQLite(ctx, filepath.Join(t.TempDir(), "platformd.sqlite"))
	if err != nil {
		t.Fatalf("open test SQLite: %v", err)
	}
	if _, err := database.Migrate(ctx, db, migrations.Files); err != nil {
		db.Close()
		t.Fatalf("migrate test SQLite: %v", err)
	}
	harness.db = db

	handler := NewHandler(Store{
		DB:             db,
		FilesDir:       harness.filesDir,
		MaxUploadBytes: maxUploadBytes,
		Logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		RequireSession: func(*http.Request) (Session, bool) {
			if !harness.sessionOK {
				return Session{}, false
			}
			return Session{SubjectID: filesTestSubjectID}, true
		},
	})
	harness.mux = http.NewServeMux()
	handler.Register(harness.mux)
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Logf("close test SQLite: %v", err)
		}
	})
	return harness
}

type filesTestPart struct {
	FieldName    string
	FileName     string
	OmitFileName bool
	ContentType  string
	Content      []byte
}

func filesTestMultipartBody(t *testing.T, parts ...filesTestPart) ([]byte, string) {
	t.Helper()
	var buffer bytes.Buffer
	writer := multipart.NewWriter(&buffer)
	for _, part := range parts {
		header := make(textproto.MIMEHeader)
		disposition := fmt.Sprintf("form-data; name=%q", part.FieldName)
		if !part.OmitFileName {
			disposition += fmt.Sprintf("; filename=%q", part.FileName)
		}
		header.Set("Content-Disposition", disposition)
		if part.ContentType != "" {
			header.Set("Content-Type", part.ContentType)
		}
		writerPart, err := writer.CreatePart(header)
		if err != nil {
			t.Fatalf("create multipart part: %v", err)
		}
		if _, err := writerPart.Write(part.Content); err != nil {
			t.Fatalf("write multipart part: %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close multipart writer: %v", err)
	}
	return buffer.Bytes(), writer.FormDataContentType()
}

// filesTestMetadata 是契约形状的独立副本：不依赖实现的 fileMetadata 结构体。
type filesTestMetadata struct {
	ID           string `json:"id"`
	OriginalName string `json:"original_name"`
	StoredName   string `json:"stored_name"`
	MimeType     string `json:"mime_type"`
	SizeBytes    int64  `json:"size_bytes"`
	StoragePath  string `json:"storage_path"`
	HashSHA256   string `json:"hash_sha256"`
	UploadedBy   string `json:"uploaded_by"`
	CreatedAt    string `json:"created_at"`
}

type filesTestProblem struct {
	Type               string `json:"type"`
	Title              string `json:"title"`
	Status             int    `json:"status"`
	Detail             string `json:"detail"`
	RequiredPermission string `json:"required_permission"`
}

func filesTestDecodeMetadata(t *testing.T, recorder *httptest.ResponseRecorder) filesTestMetadata {
	t.Helper()
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", got)
	}
	var metadata filesTestMetadata
	if err := json.Unmarshal(recorder.Body.Bytes(), &metadata); err != nil {
		t.Fatalf("decode file metadata: %v; body = %s", err, recorder.Body.String())
	}
	return metadata
}

func filesTestDecodeMetadataList(t *testing.T, recorder *httptest.ResponseRecorder) []filesTestMetadata {
	t.Helper()
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", got)
	}
	var files []filesTestMetadata
	if err := json.Unmarshal(recorder.Body.Bytes(), &files); err != nil {
		t.Fatalf("decode file list: %v; body = %s", err, recorder.Body.String())
	}
	return files
}

func filesTestRequireProblem(t *testing.T, recorder *httptest.ResponseRecorder, status int, slug, detail string) filesTestProblem {
	t.Helper()
	if recorder.Code != status {
		t.Fatalf("status = %d, want %d; body = %s", recorder.Code, status, recorder.Body.String())
	}
	if got := recorder.Header().Get("Content-Type"); got != filesTestProblemType {
		t.Fatalf("Content-Type = %q, want %q", got, filesTestProblemType)
	}
	var problem filesTestProblem
	if err := json.Unmarshal(recorder.Body.Bytes(), &problem); err != nil {
		t.Fatalf("decode problem details: %v; body = %s", err, recorder.Body.String())
	}
	if problem.Type != Prefix+"/problems/"+slug {
		t.Fatalf("problem type = %q, want %q", problem.Type, Prefix+"/problems/"+slug)
	}
	if problem.Status != status {
		t.Fatalf("problem status = %d, want %d", problem.Status, status)
	}
	if problem.Detail != detail {
		t.Fatalf("problem detail = %q, want %q", problem.Detail, detail)
	}
	return problem
}

func TestFilesUploadPersistsContentMetadataAndPermissions(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)
	content := []byte("care plan")

	recorder := harness.uploadOne("file", "care-plan.txt", "text/plain", content)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	metadata := filesTestDecodeMetadata(t, recorder)

	if !filesTestULIDPattern.MatchString(metadata.ID) {
		t.Fatalf("id = %q, want 26-char ULID", metadata.ID)
	}
	if metadata.StoredName != metadata.ID {
		t.Fatalf("stored_name = %q, want server ULID %q", metadata.StoredName, metadata.ID)
	}
	if metadata.StoragePath != metadata.StoredName {
		t.Fatalf("storage_path = %q, want stored_name %q", metadata.StoragePath, metadata.StoredName)
	}
	if metadata.OriginalName != "care-plan.txt" {
		t.Fatalf("original_name = %q", metadata.OriginalName)
	}
	if metadata.MimeType != "text/plain" {
		t.Fatalf("mime_type = %q", metadata.MimeType)
	}
	if metadata.SizeBytes != int64(len(content)) {
		t.Fatalf("size_bytes = %d, want %d", metadata.SizeBytes, len(content))
	}
	if metadata.UploadedBy != filesTestSubjectID {
		t.Fatalf("uploaded_by = %q, want %q", metadata.UploadedBy, filesTestSubjectID)
	}
	digest := sha256.Sum256(content)
	if wanted := hex.EncodeToString(digest[:]); metadata.HashSHA256 != wanted {
		t.Fatalf("hash_sha256 = %q, want %q", metadata.HashSHA256, wanted)
	}
	if _, err := time.Parse(timestampLayout, metadata.CreatedAt); err != nil {
		t.Fatalf("created_at = %q is not a Nexus timestamp: %v", metadata.CreatedAt, err)
	}

	// 物理文件：服务端 ULID 命名、内容一致、0640；目录 0750。
	diskPath := filepath.Join(harness.filesDir, metadata.StoredName)
	storedContent, err := os.ReadFile(diskPath)
	if err != nil {
		t.Fatalf("read stored file %q: %v", diskPath, err)
	}
	if !bytes.Equal(storedContent, content) {
		t.Fatalf("stored content = %q, want %q", storedContent, content)
	}
	fileInfo, err := os.Stat(diskPath)
	if err != nil {
		t.Fatalf("stat stored file: %v", err)
	}
	if mode := fileInfo.Mode().Perm(); mode != 0o640 {
		t.Fatalf("stored file mode = %o, want 0640", mode)
	}
	directoryInfo, err := os.Stat(harness.filesDir)
	if err != nil {
		t.Fatalf("stat files directory: %v", err)
	}
	if mode := directoryInfo.Mode().Perm(); mode != 0o750 {
		t.Fatalf("files directory mode = %o, want 0750", mode)
	}

	// 元数据落库，且与磁盘同源。
	if got := harness.rowCount("SELECT COUNT(*) FROM files WHERE id = ?", metadata.ID); got != 1 {
		t.Fatalf("files row count = %d, want 1", got)
	}
	var storedPath string
	if err := harness.db.QueryRowContext(t.Context(), "SELECT storage_path FROM files WHERE id = ?", metadata.ID).Scan(&storedPath); err != nil {
		t.Fatalf("query storage_path: %v", err)
	}
	if storedPath != metadata.StoredName {
		t.Fatalf("db storage_path = %q, want %q", storedPath, metadata.StoredName)
	}
}

func TestFilesUploadMissingContentTypeFallsBackToOctetStream(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)

	recorder := harness.uploadOne("file", "blob.bin", "", []byte("blob"))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := filesTestDecodeMetadata(t, recorder).MimeType; got != "application/octet-stream" {
		t.Fatalf("mime_type = %q, want application/octet-stream", got)
	}
}

func TestFilesUploadDoesNotUseClientFileNameAsPath(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)

	// Go 的 multipart.Part.FileName 会做 filepath.Base 归一化：客户端路径不会落盘。
	recorder := harness.uploadOne("file", "../../etc/passwd", "text/plain", []byte("escape"))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	metadata := filesTestDecodeMetadata(t, recorder)
	if metadata.OriginalName != "passwd" {
		t.Fatalf("original_name = %q, want filepath.Base result passwd", metadata.OriginalName)
	}
	if !filesTestULIDPattern.MatchString(metadata.StoredName) {
		t.Fatalf("stored_name = %q, want server ULID", metadata.StoredName)
	}
	if _, err := os.Stat(filepath.Join(harness.filesDir, metadata.StoredName)); err != nil {
		t.Fatalf("stored file missing inside FilesDir: %v", err)
	}
	// FilesDir 之外不应出现逃逸文件（父目录由 t.TempDir 独占）。
	entries, err := os.ReadDir(filepath.Dir(harness.filesDir))
	if err != nil {
		t.Fatalf("read temp parent: %v", err)
	}
	if len(entries) > 1 {
		t.Fatalf("unexpected entries next to FilesDir: %v", entries)
	}
}

func TestFilesListFiltersOrdersAndPaginates(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)

	// 直接造行：created_at 明确可控，顺序断言不受同毫秒并列影响。
	rows := []struct {
		id         string
		uploadedBy string
		createdAt  string
	}{
		{"01M3ZR3CDAQ6ZKHCYD065RSY01", filesTestSubjectID, "2026-01-01T00:00:00.001Z"},
		{"01M3ZR3CDAQ6ZKHCYD065RSY02", filesTestSubjectID, "2026-01-01T00:00:00.002Z"},
		{"01M3ZR3CDAQ6ZKHCYD065RSY03", filesTestOtherSubjectID, "2026-01-01T00:00:00.003Z"},
		{"01M3ZR3CDAQ6ZKHCYD065RSY04", filesTestSubjectID, "2026-01-01T00:00:00.004Z"},
		{"01M3ZR3CDAQ6ZKHCYD065RSY05", filesTestOtherSubjectID, "2026-01-01T00:00:00.005Z"},
	}
	for _, row := range rows {
		harness.insertFileMetadata(row.id, row.uploadedBy, row.createdAt, row.id, "text/plain")
	}

	listed := harness.request(http.MethodGet, Prefix+"/files")
	if listed.Code != http.StatusOK {
		t.Fatalf("list status = %d, want 200; body = %s", listed.Code, listed.Body.String())
	}
	files := filesTestDecodeMetadataList(t, listed)
	if len(files) != len(rows) {
		t.Fatalf("list length = %d, want %d", len(files), len(rows))
	}
	wantOrder := []string{rows[4].id, rows[3].id, rows[2].id, rows[1].id, rows[0].id}
	for index, wanted := range wantOrder {
		if files[index].ID != wanted {
			t.Fatalf("list[%d].id = %q, want %q (created_at DESC)", index, files[index].ID, wanted)
		}
	}

	// uploaded_by 过滤；空串不过滤（Nexus 只过滤非空值）。
	filtered := filesTestDecodeMetadataList(t, harness.request(http.MethodGet, Prefix+"/files?uploaded_by="+filesTestSubjectID))
	if len(filtered) != 3 {
		t.Fatalf("filtered length = %d, want 3", len(filtered))
	}
	for _, file := range filtered {
		if file.UploadedBy != filesTestSubjectID {
			t.Fatalf("filtered uploaded_by = %q, want %q", file.UploadedBy, filesTestSubjectID)
		}
	}
	if got := len(filesTestDecodeMetadataList(t, harness.request(http.MethodGet, Prefix+"/files?uploaded_by="))); got != len(rows) {
		t.Fatalf("empty uploaded_by should not filter: length = %d, want %d", got, len(rows))
	}

	// page/page_size：page 从 1 开始，created_at DESC 后切页。
	firstPage := filesTestDecodeMetadataList(t, harness.request(http.MethodGet, Prefix+"/files?page=1&page_size=2"))
	if len(firstPage) != 2 || firstPage[0].ID != rows[4].id || firstPage[1].ID != rows[3].id {
		t.Fatalf("page 1 = %+v, want ids %s,%s", firstPage, rows[4].id, rows[3].id)
	}
	secondPage := filesTestDecodeMetadataList(t, harness.request(http.MethodGet, Prefix+"/files?page=2&page_size=2"))
	if len(secondPage) != 2 || secondPage[0].ID != rows[2].id || secondPage[1].ID != rows[1].id {
		t.Fatalf("page 2 = %+v, want ids %s,%s", secondPage, rows[2].id, rows[1].id)
	}
	thirdPage := filesTestDecodeMetadataList(t, harness.request(http.MethodGet, Prefix+"/files?page=3&page_size=2"))
	if len(thirdPage) != 1 || thirdPage[0].ID != rows[0].id {
		t.Fatalf("page 3 = %+v, want id %s", thirdPage, rows[0].id)
	}
	emptyPage := harness.request(http.MethodGet, Prefix+"/files?page=4&page_size=2")
	if emptyPage.Code != http.StatusOK {
		t.Fatalf("empty page status = %d, want 200", emptyPage.Code)
	}
	if body := strings.TrimSpace(emptyPage.Body.String()); body != "[]" {
		t.Fatalf("empty list body = %q, want []", body)
	}

	// 尾斜杠兼容：/files 与 /files/ 都是列表。
	if got := harness.request(http.MethodGet, Prefix+"/files/"); got.Code != http.StatusOK {
		t.Fatalf("trailing slash list status = %d, want 200; body = %s", got.Code, got.Body.String())
	}

	// 非法分页 400，detail 与 Nexus 一致。
	for _, path := range []string{
		Prefix + "/files?page=0",
		Prefix + "/files?page_size=0",
		Prefix + "/files?page_size=101",
		Prefix + "/files?page=abc",
	} {
		filesTestRequireProblem(t, harness.request(http.MethodGet, path), http.StatusBadRequest,
			"invalid-request", "page must be positive and page_size must be between 1 and 100")
	}
}

func TestFilesGetAndDownload(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)
	content := []byte("pdf-bytes")

	uploaded := harness.uploadOne("file", "护理计划.pdf", "application/pdf", content)
	if uploaded.Code != http.StatusCreated {
		t.Fatalf("upload status = %d, want 201; body = %s", uploaded.Code, uploaded.Body.String())
	}
	metadata := filesTestDecodeMetadata(t, uploaded)

	fetched := harness.request(http.MethodGet, Prefix+"/files/"+metadata.ID)
	if fetched.Code != http.StatusOK {
		t.Fatalf("get status = %d, want 200; body = %s", fetched.Code, fetched.Body.String())
	}
	if got := filesTestDecodeMetadata(t, fetched); got.ID != metadata.ID {
		t.Fatalf("fetched id = %q, want %q", got.ID, metadata.ID)
	}

	download := harness.request(http.MethodGet, Prefix+"/files/"+metadata.ID+"/content")
	if download.Code != http.StatusOK {
		t.Fatalf("download status = %d, want 200; body = %s", download.Code, download.Body.String())
	}
	if got := download.Header().Get("Content-Type"); got != "application/pdf" {
		t.Fatalf("download Content-Type = %q, want application/pdf", got)
	}
	wantDisposition := "attachment; filename=\"____.pdf\"; filename*=UTF-8''%E6%8A%A4%E7%90%86%E8%AE%A1%E5%88%92.pdf"
	if got := download.Header().Get("Content-Disposition"); got != wantDisposition {
		t.Fatalf("Content-Disposition = %q, want %q", got, wantDisposition)
	}
	if !bytes.Equal(download.Body.Bytes(), content) {
		t.Fatalf("download body = %q, want %q", download.Body.Bytes(), content)
	}

	// 元数据缺失。
	filesTestRequireProblem(t, harness.request(http.MethodGet, Prefix+"/files/missing-file"),
		http.StatusNotFound, "not-found", "file not found")
	filesTestRequireProblem(t, harness.request(http.MethodGet, Prefix+"/files/missing-file/content"),
		http.StatusNotFound, "not-found", "file not found")

	// 元数据在但磁盘文件缺失。
	missingDiskID := "01M3ZR3CDAQ6ZKHCYD065RSY0A"
	harness.insertFileMetadata(missingDiskID, filesTestSubjectID,
		"2026-01-01T00:00:00.000Z", missingDiskID, "text/plain")
	filesTestRequireProblem(t, harness.request(http.MethodGet, Prefix+"/files/"+missingDiskID+"/content"),
		http.StatusNotFound, "not-found", "stored file not found")
}

func TestFilesDownloadInvalidStoredMimeFallsBackToOctetStream(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)
	storedName := "01M3ZR3CDAQ6ZKHCYD065RSY0B"
	if err := os.MkdirAll(harness.filesDir, 0o750); err != nil {
		t.Fatalf("create files dir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(harness.filesDir, storedName), []byte("body"), 0o640); err != nil {
		t.Fatalf("write stored file: %v", err)
	}
	harness.insertFileMetadata(storedName, filesTestSubjectID, "2026-01-01T00:00:00.000Z", storedName, "text/plain\r\nX-Evil: 1")

	download := harness.request(http.MethodGet, Prefix+"/files/"+storedName+"/content")
	if download.Code != http.StatusOK {
		t.Fatalf("download status = %d, want 200; body = %s", download.Code, download.Body.String())
	}
	if got := download.Header().Get("Content-Type"); got != "application/octet-stream" {
		t.Fatalf("Content-Type = %q, want application/octet-stream fallback", got)
	}
}

func TestFilesDeleteRemovesFileThenMetadata(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)

	uploaded := harness.uploadOne("file", "care-plan.txt", "text/plain", []byte("care plan"))
	if uploaded.Code != http.StatusCreated {
		t.Fatalf("upload status = %d, want 201; body = %s", uploaded.Code, uploaded.Body.String())
	}
	metadata := filesTestDecodeMetadata(t, uploaded)
	diskPath := filepath.Join(harness.filesDir, metadata.StoredName)

	deleted := harness.request(http.MethodDelete, Prefix+"/files/"+metadata.ID)
	if deleted.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d, want 204; body = %s", deleted.Code, deleted.Body.String())
	}
	if deleted.Body.Len() != 0 {
		t.Fatalf("delete body = %q, want empty", deleted.Body.String())
	}
	if _, err := os.Stat(diskPath); !os.IsNotExist(err) {
		t.Fatalf("stored file should be physically deleted, stat err = %v", err)
	}
	if got := harness.rowCount("SELECT COUNT(*) FROM files WHERE id = ?", metadata.ID); got != 0 {
		t.Fatalf("files row count = %d, want 0", got)
	}

	// 已删除：再查/再删都是 404 file not found。
	filesTestRequireProblem(t, harness.request(http.MethodGet, Prefix+"/files/"+metadata.ID),
		http.StatusNotFound, "not-found", "file not found")
	filesTestRequireProblem(t, harness.request(http.MethodDelete, Prefix+"/files/"+metadata.ID),
		http.StatusNotFound, "not-found", "file not found")

	// 元数据在、磁盘文件缺失：删除仍 204 并清掉元数据（Nexus 忽略 NotFound）。
	missingDiskID := "01M3ZR3CDAQ6ZKHCYD065RSY0C"
	harness.insertFileMetadata(missingDiskID, filesTestSubjectID, "2026-01-01T00:00:00.000Z", missingDiskID, "text/plain")
	if got := harness.request(http.MethodDelete, Prefix+"/files/"+missingDiskID); got.Code != http.StatusNoContent {
		t.Fatalf("delete with missing disk file status = %d, want 204; body = %s", got.Code, got.Body.String())
	}
	if got := harness.rowCount("SELECT COUNT(*) FROM files WHERE id = ?", missingDiskID); got != 0 {
		t.Fatalf("files row count after delete = %d, want 0", got)
	}
}

func TestFilesUploadValidation(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)

	t.Run("empty multipart", func(t *testing.T) {
		filesTestRequireProblem(t, harness.upload(), http.StatusBadRequest, "invalid-request", "file is required")
	})
	t.Run("wrong field name", func(t *testing.T) {
		filesTestRequireProblem(t, harness.uploadOne("upload", "care-plan.txt", "text/plain", []byte("x")),
			http.StatusBadRequest, "invalid-request", "multipart field must be named file")
	})
	t.Run("missing file name", func(t *testing.T) {
		filesTestRequireProblem(t, harness.upload(filesTestPart{
			FieldName: "file", OmitFileName: true, ContentType: "text/plain", Content: []byte("x"),
		}), http.StatusBadRequest, "invalid-request", "file name is required")
	})
	t.Run("two files", func(t *testing.T) {
		filesTestRequireProblem(t, harness.upload(
			filesTestPart{FieldName: "file", FileName: "a.txt", ContentType: "text/plain", Content: []byte("a")},
			filesTestPart{FieldName: "file", FileName: "b.txt", ContentType: "text/plain", Content: []byte("b")},
		), http.StatusBadRequest, "invalid-request", "only one file is accepted per upload")
	})
	t.Run("not multipart", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodPost, Prefix+"/files/upload", strings.NewReader("{}"))
		request.Header.Set("Content-Type", "application/json")
		filesTestRequireProblem(t, harness.serve(request), http.StatusBadRequest,
			"invalid-request", "invalid multipart form data")
	})
}

func TestFilesUploadRejectsOversizedBody(t *testing.T) {
	harness := newFilesTestHarness(t, 64)

	recorder := harness.uploadOne("file", "big.txt", "text/plain", bytes.Repeat([]byte("x"), 256))
	filesTestRequireProblem(t, recorder, http.StatusRequestEntityTooLarge, "payload-too-large", "payload too large")

	if got := harness.rowCount("SELECT COUNT(*) FROM files"); got != 0 {
		t.Fatalf("files row count = %d, want 0 after rejected upload", got)
	}
	entries, err := os.ReadDir(harness.filesDir)
	if err != nil && !os.IsNotExist(err) {
		t.Fatalf("read files directory: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("files directory has %d entries after rejected upload, want 0", len(entries))
	}
}

func TestFilesRoutesRequireSession(t *testing.T) {
	harness := newFilesTestHarness(t, 1<<20)
	fileID := "01M3ZR3CDAQ6ZKHCYD065RSY0D"
	harness.insertFileMetadata(fileID, filesTestSubjectID,
		"2026-01-01T00:00:00.000Z", fileID, "text/plain")
	harness.sessionOK = false

	requests := []struct {
		method string
		path   string
	}{
		{http.MethodGet, Prefix + "/files"},
		{http.MethodPost, Prefix + "/files/upload"},
		{http.MethodGet, Prefix + "/files/" + fileID},
		{http.MethodGet, Prefix + "/files/" + fileID + "/content"},
		{http.MethodDelete, Prefix + "/files/" + fileID},
	}
	for _, request := range requests {
		filesTestRequireProblem(t, harness.request(request.method, request.path),
			http.StatusUnauthorized, "not-authenticated", "not authenticated")
	}
}

func TestFilesStoredFilePathRejectsTraversal(t *testing.T) {
	handler := &Handler{store: Store{FilesDir: "/tmp/files"}}
	for _, storedName := range []string{"", ".", "..", "a/b", "../escape", "/etc/passwd", "dir/../file"} {
		if _, err := handler.storedFilePath(storedName); err == nil {
			t.Fatalf("storedFilePath(%q) should fail", storedName)
		}
	}
	path, err := handler.storedFilePath("01M3ZR3CDAQ6ZKHCYD065RSYMT")
	if err != nil {
		t.Fatalf("storedFilePath(valid ULID) = %v", err)
	}
	if path != "/tmp/files/01M3ZR3CDAQ6ZKHCYD065RSYMT" {
		t.Fatalf("storedFilePath = %q", path)
	}
}

// 与 Rust src/files/mod.rs 的单元测试逐条对齐。
func TestFilesContentDispositionMatchesNexus(t *testing.T) {
	if got := safeFilename("record\r\nX-Test: yes"); got != "record__X-Test:_yes" {
		t.Fatalf("safeFilename = %q, want record__X-Test:_yes", got)
	}
	disposition := contentDisposition("护理计划.pdf")
	if disposition != "attachment; filename=\"____.pdf\"; filename*=UTF-8''%E6%8A%A4%E7%90%86%E8%AE%A1%E5%88%92.pdf" {
		t.Fatalf("Content-Disposition = %q", disposition)
	}
	if got := contentDisposition("care-plan.txt"); got != "attachment; filename=\"care-plan.txt\"; filename*=UTF-8''care-plan.txt" {
		t.Fatalf("Content-Disposition = %q", got)
	}
}
