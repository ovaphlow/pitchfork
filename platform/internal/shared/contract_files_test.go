package shared_test

// 对照 service-nexus-shared/tests/identity_backed_resources.rs 与 src/files/mod.rs：
// multipart 字段必须名为 file、上传 201、下载 Content-Type/Content-Disposition 同 Nexus、
// 删除先盘后库（204 后物理文件与元数据都不在）、超过 MaxUploadBytes -> 413。

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strings"
	"testing"
)

func TestContractFilesUploadDownloadDelete(t *testing.T) {
	harness := newContractHarness(t)
	content := []byte("care plan")
	digest := sha256.Sum256(content)
	wantHash := hex.EncodeToString(digest[:])

	uploaded := harness.uploadFile(contractStaffSession, "file", "care-plan.txt", "text/plain", content)
	uploaded.requireStatus(http.StatusCreated)
	metadata := uploaded.object()
	fileID := contractULIDField(t, metadata, "id")
	if got := contractString(t, metadata, "original_name"); got != "care-plan.txt" {
		t.Fatalf("original_name = %q", got)
	}
	if got := contractString(t, metadata, "stored_name"); got != fileID {
		t.Fatalf("stored_name = %q, want server ULID %q", got, fileID)
	}
	if got := contractString(t, metadata, "mime_type"); got != "text/plain" {
		t.Fatalf("mime_type = %q", got)
	}
	if got := contractNumber(t, metadata, "size_bytes"); got != float64(len(content)) {
		t.Fatalf("size_bytes = %v, want %d", got, len(content))
	}
	if got := contractString(t, metadata, "hash_sha256"); got != wantHash {
		t.Fatalf("hash_sha256 = %q, want %q", got, wantHash)
	}
	if got := contractString(t, metadata, "uploaded_by"); got != contractStaffID {
		t.Fatalf("uploaded_by = %q, want %q", got, contractStaffID)
	}
	if got := contractString(t, metadata, "storage_path"); got != contractString(t, metadata, "stored_name") {
		t.Fatalf("storage_path = %q, want stored_name", got)
	}
	contractTimestampField(t, metadata, "created_at")

	storedName := contractString(t, metadata, "stored_name")
	storedContent, err := harness.readStoredFile(t, storedName)
	if err != nil {
		t.Fatalf("read stored file %q: %v", storedName, err)
	}
	if string(storedContent) != string(content) {
		t.Fatalf("stored file content = %q, want %q", storedContent, content)
	}

	listed := harness.request(http.MethodGet, sharedPath("/files"), contractStaffSession, nil)
	listed.requireStatus(http.StatusOK)
	if got := len(listed.array()); got != 1 {
		t.Fatalf("files list length = %d, want 1", got)
	}

	fetched := harness.request(http.MethodGet, sharedPath("/files/"+fileID), contractStaffSession, nil)
	fetched.requireStatus(http.StatusOK)
	if got := contractString(t, fetched.object(), "id"); got != fileID {
		t.Fatalf("fetched id = %q", got)
	}

	// 下载：Content-Type/Content-Disposition 与 Nexus 一致。
	download := harness.request(http.MethodGet, sharedPath("/files/"+fileID+"/content"), contractStaffSession, nil)
	download.requireStatus(http.StatusOK)
	if got := string(download.body); got != string(content) {
		t.Fatalf("download body = %q, want %q", got, content)
	}
	if got := download.header.Get("Content-Type"); !strings.HasPrefix(got, "text/plain") {
		t.Fatalf("download Content-Type = %q, want text/plain", got)
	}
	disposition := download.header.Get("Content-Disposition")
	if !strings.Contains(disposition, "attachment;") {
		t.Fatalf("Content-Disposition = %q, want attachment", disposition)
	}
	if !strings.Contains(disposition, `filename="care-plan.txt"`) {
		t.Fatalf("Content-Disposition = %q, want filename=\"care-plan.txt\"", disposition)
	}
	if !strings.Contains(disposition, "filename*=UTF-8''care-plan.txt") {
		t.Fatalf("Content-Disposition = %q, want RFC 5987 filename*", disposition)
	}

	// 先盘后库：删除后物理文件与元数据都不存在。
	deleted := harness.request(http.MethodDelete, sharedPath("/files/"+fileID), contractStaffSession, nil)
	deleted.requireStatus(http.StatusNoContent)
	if _, err := harness.readStoredFile(t, storedName); err == nil {
		t.Fatalf("stored file %q should be physically deleted", storedName)
	}
	if got := harness.rowCount(t, "SELECT COUNT(*) FROM files WHERE id = ?", fileID); got != 0 {
		t.Fatalf("files row count for %q = %d, want 0", fileID, got)
	}

	downloadGone := harness.request(http.MethodGet, sharedPath("/files/"+fileID+"/content"), contractStaffSession, nil)
	downloadGone.requireProblem(http.StatusNotFound, "not-found", "")

	metadataGone := harness.request(http.MethodGet, sharedPath("/files/"+fileID), contractStaffSession, nil)
	metadataGone.requireProblem(http.StatusNotFound, "not-found", "")

	deleteMissing := harness.request(http.MethodDelete, sharedPath("/files/"+fileID), contractStaffSession, nil)
	deleteMissing.requireProblem(http.StatusNotFound, "not-found", "")
}

func TestContractFilesUploadValidation(t *testing.T) {
	harness := newContractHarness(t)

	// multipart 字段必须名为 file。
	wrongField := harness.uploadFile(contractStaffSession, "upload", "care-plan.txt", "text/plain", []byte("care plan"))
	wrongField.requireProblem(http.StatusBadRequest, "invalid-request", "")

	// 超过 MaxUploadBytes -> 413。
	small := newContractHarnessWithUploadLimit(t, 8)
	tooLarge := small.uploadFile(contractStaffSession, "file", "big.txt", "text/plain", []byte("0123456789"))
	tooLarge.requireStatus(http.StatusRequestEntityTooLarge)

	// 非 ASCII 原始文件名的 Content-Disposition（Nexus inline test 的可观察部分）。
	uploaded := harness.uploadFile(contractStaffSession, "file", "护理计划.pdf", "application/pdf", []byte("pdf"))
	uploaded.requireStatus(http.StatusCreated)
	metadata := uploaded.object()
	download := harness.request(http.MethodGet, sharedPath("/files/"+contractString(t, metadata, "id")+"/content"), contractStaffSession, nil)
	download.requireStatus(http.StatusOK)
	disposition := download.header.Get("Content-Disposition")
	if !strings.Contains(disposition, `filename="____.pdf"`) {
		t.Fatalf("Content-Disposition = %q, want ASCII-safe filename=\"____.pdf\"", disposition)
	}
	if !strings.Contains(disposition, "filename*=UTF-8''%E6%8A%A4%E7%90%86%E8%AE%A1%E5%88%92.pdf") {
		t.Fatalf("Content-Disposition = %q, want RFC 5987 encoded original name", disposition)
	}
}
