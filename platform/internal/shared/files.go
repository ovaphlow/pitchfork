package shared

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// files 模块实现 /crate-api/v1/files（055：迁自 service-nexus-shared/src/files/mod.rs）。
//
// 契约要点（逐条对齐 Rust 实现）：
//   - GET /             直接返回数组（不是 records+meta 信封），支持 uploaded_by/page/page_size，
//                       按 created_at DESC 排序；
//   - POST /upload      multipart/form-data，字段名必须为 file；存储名由服务端 ULID 生成，
//                       不使用客户端文件名做路径；返回 201 + 元数据；
//   - GET /{id}         元数据，缺失 404 "file not found"；
//   - GET /{id}/content 文件实体，Content-Type 取存储的 mime_type，Content-Disposition 与
//                       Rust 的 filename/filename* 双写法一致；磁盘缺失 404 "stored file not found"；
//   - DELETE /{id}      先物理删除磁盘文件（缺失视为成功）再删除元数据，204。
//
// storage_path 只存相对文件名（与 stored_name 相同），磁盘路径为 FilesDir/storage_path；
// 目录 0750、文件 0640。上传大小上限为 Store.MaxUploadBytes，超限 413 payload-too-large。

// defaultMaxUploadBytes 与 Nexus 的 NEXUS_MAX_UPLOAD_BYTES 默认值一致；
// 仅在 Store.MaxUploadBytes 未配置时兜底（生产配置一定为正数）。
const defaultMaxUploadBytes int64 = 20 * 1024 * 1024

const fileColumns = "id, app_code, original_name, stored_name, mime_type, size_bytes, storage_path, hash_sha256, uploaded_by, created_at"

const insertFileSQL = "INSERT INTO files (id, app_code, original_name, stored_name, mime_type, size_bytes, storage_path, hash_sha256, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

// fileMetadata 的字段顺序与 Rust FileMetadata 完全一致。
type fileMetadata struct {
	ID           string `json:"id"`
	AppCode      string `json:"app_code"`
	OriginalName string `json:"original_name"`
	StoredName   string `json:"stored_name"`
	MimeType     string `json:"mime_type"`
	SizeBytes    int64  `json:"size_bytes"`
	StoragePath  string `json:"storage_path"`
	HashSHA256   string `json:"hash_sha256"`
	UploadedBy   string `json:"uploaded_by"`
	CreatedAt    string `json:"created_at"`
}

// errInvalidStoredName 表示库中的 stored_name 不能安全地拼进 FilesDir（路径穿越防线）。
var errInvalidStoredName = errors.New("invalid stored file name")

// registerFiles 挂载 /files（files.go）。
func (handler *Handler) registerFiles(mux *http.ServeMux) {
	// Rust 的 nest("/files") + route("/") 两种形态都要接受：显式注册带/不带尾斜杠。
	mux.HandleFunc("GET "+Prefix+"/files", handler.listFiles)
	mux.HandleFunc("GET "+Prefix+"/files/{$}", handler.listFiles)
	mux.HandleFunc("POST "+Prefix+"/files/upload", handler.uploadFile)
	mux.HandleFunc("GET "+Prefix+"/files/{id}", handler.getFile)
	mux.HandleFunc("GET "+Prefix+"/files/{id}/content", handler.downloadFile)
	mux.HandleFunc("DELETE "+Prefix+"/files/{id}", handler.deleteFile)
}

// listFiles 返回文件元数据数组（裸数组，与 Nexus 一致）。
func (handler *Handler) listFiles(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, appFailure := handler.requireAppCode(request)
	if appFailure != (apiError{}) {
		handler.fail(responseWriter, appFailure)
		return
	}
	limit, offset, queryError := pageQuery(request)
	if queryError.status != 0 {
		handler.fail(responseWriter, queryError)
		return
	}

	// Nexus 只把非空 uploaded_by 当作过滤条件（不做 trim）。
	uploadedBy := request.URL.Query().Get("uploaded_by")
	query := "SELECT " + fileColumns + " FROM files WHERE app_code = ?"
	arguments := []any{appCode}
	if uploadedBy != "" {
		query += " AND uploaded_by = ?"
		arguments = append(arguments, uploadedBy)
	}
	query += " ORDER BY created_at DESC LIMIT ? OFFSET ?"
	arguments = append(arguments, limit, offset)

	rows, err := handler.store.DB.QueryContext(request.Context(), query, arguments...)
	if err != nil {
		handler.failFiles(responseWriter, request, "list files", err)
		return
	}
	defer rows.Close()

	files := make([]fileMetadata, 0, int(limit))
	for rows.Next() {
		file, err := scanFileMetadata(rows.Scan)
		if err != nil {
			handler.failFiles(responseWriter, request, "scan file metadata", err)
			return
		}
		files = append(files, file)
	}
	if err := rows.Err(); err != nil {
		handler.failFiles(responseWriter, request, "iterate files", err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, files)
}

// uploadFile 接收 multipart 上传：字段名 file、服务端 ULID 存储名、SHA-256 元数据。
func (handler *Handler) uploadFile(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return
	}
	appCode, appFailure := handler.requireAppCode(request)
	if appFailure != (apiError{}) {
		handler.fail(responseWriter, appFailure)
		return
	}

	limit := handler.store.MaxUploadBytes
	if limit <= 0 {
		limit = defaultMaxUploadBytes
	}
	// 与 Nexus 的 DefaultBodyLimit::max(max_upload_bytes) 等价：限制整个 multipart 请求体。
	request.Body = http.MaxBytesReader(responseWriter, request.Body, limit)
	defer request.Body.Close()

	reader, err := request.MultipartReader()
	if err != nil {
		handler.fail(responseWriter, badRequest("invalid multipart form data"))
		return
	}
	part, err := reader.NextPart()
	if errors.Is(err, io.EOF) {
		handler.fail(responseWriter, badRequest("file is required"))
		return
	}
	if err != nil {
		handler.fail(responseWriter, multipartReadError(err))
		return
	}
	defer part.Close()

	if part.FormName() != "file" {
		handler.fail(responseWriter, badRequest("multipart field must be named file"))
		return
	}
	// Go 的 Part.FileName 会做 filepath.Base 归一化（更安全）；Rust 保留原始文件名，
	// 差异只出现在客户端文件名含路径分隔符时。
	originalName := part.FileName()
	if originalName == "" {
		handler.fail(responseWriter, badRequest("file name is required"))
		return
	}
	mimeType := part.Header.Get("Content-Type")
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}

	contents, err := io.ReadAll(part)
	if err != nil {
		if isBodyTooLarge(err) {
			handler.fail(responseWriter, payloadTooLarge())
			return
		}
		handler.fail(responseWriter, badRequest("could not read uploaded file"))
		return
	}

	// Nexus 只接受单个文件字段；读取后再确认没有第二个字段。
	nextPart, nextErr := reader.NextPart()
	if nextErr == nil {
		_ = nextPart.Close()
		handler.fail(responseWriter, badRequest("only one file is accepted per upload"))
		return
	}
	if !errors.Is(nextErr, io.EOF) {
		handler.fail(responseWriter, multipartReadError(nextErr))
		return
	}

	id, err := newID()
	if err != nil {
		handler.failFiles(responseWriter, request, "generate file id", err)
		return
	}
	storedName := id
	storagePath := storedName
	diskPath, err := handler.storedFilePath(storedName)
	if err != nil {
		handler.failFiles(responseWriter, request, "resolve upload path", err)
		return
	}
	// 目录 0750（main.go 已建；测试/嵌入式场景兜底）。
	if err := os.MkdirAll(handler.store.FilesDir, 0o750); err != nil {
		handler.failFiles(responseWriter, request, "create files directory", err)
		return
	}
	digest := sha256.Sum256(contents)
	hashSHA256 := hex.EncodeToString(digest[:])
	if err := os.WriteFile(diskPath, contents, 0o640); err != nil {
		handler.failFiles(responseWriter, request, "write uploaded file", err)
		return
	}

	_, err = handler.store.DB.ExecContext(request.Context(), insertFileSQL,
		id, appCode, originalName, storedName, mimeType, int64(len(contents)), storagePath, hashSHA256, session.SubjectID)
	if err != nil {
		if removeErr := os.Remove(diskPath); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			handler.logger().LogAttrs(request.Context(), slog.LevelError, "remove untracked uploaded file",
				slog.String("path", diskPath), slog.String("error", removeErr.Error()))
		}
		handler.failFiles(responseWriter, request, "insert file metadata", err)
		return
	}

	file, err := handler.loadFile(request, id, appCode)
	if err != nil {
		handler.fail(responseWriter, notFound("file not found"))
		return
	}
	writeJSON(responseWriter, http.StatusCreated, file)
}

// getFile 返回单个文件元数据。
func (handler *Handler) getFile(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, appFailure := handler.requireAppCode(request)
	if appFailure != (apiError{}) {
		handler.fail(responseWriter, appFailure)
		return
	}
	file, err := handler.loadFile(request, request.PathValue("id"), appCode)
	if err != nil {
		handler.fail(responseWriter, notFound("file not found"))
		return
	}
	writeJSON(responseWriter, http.StatusOK, file)
}

// downloadFile 返回文件实体，响应头与 Nexus 一致。
func (handler *Handler) downloadFile(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, appFailure := handler.requireAppCode(request)
	if appFailure != (apiError{}) {
		handler.fail(responseWriter, appFailure)
		return
	}
	file, err := handler.loadFile(request, request.PathValue("id"), appCode)
	if err != nil {
		handler.fail(responseWriter, notFound("file not found"))
		return
	}
	diskPath, err := handler.storedFilePath(file.StoredName)
	if err != nil {
		// 库中路径异常：记录后按磁盘缺失处理（Nexus 的读失败语义）。
		handler.logFilesError(request, "resolve download path", err)
		handler.fail(responseWriter, notFound("stored file not found"))
		return
	}
	contents, err := os.ReadFile(diskPath)
	if err != nil {
		handler.logger().LogAttrs(request.Context(), slog.LevelError, "read stored file",
			slog.String("path", diskPath), slog.String("error", err.Error()))
		handler.fail(responseWriter, notFound("stored file not found"))
		return
	}
	mimeType := file.MimeType
	if !isValidHeaderValue(mimeType) {
		// 与 Rust HeaderValue::from_str 失败时的兜底一致。
		mimeType = "application/octet-stream"
	}
	responseWriter.Header().Set("Content-Type", mimeType)
	responseWriter.Header().Set("Content-Disposition", contentDisposition(file.OriginalName))
	responseWriter.WriteHeader(http.StatusOK)
	_, _ = responseWriter.Write(contents)
}

// deleteFile 先物理删除磁盘文件（缺失视为成功），再删除元数据。
func (handler *Handler) deleteFile(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, appFailure := handler.requireAppCode(request)
	if appFailure != (apiError{}) {
		handler.fail(responseWriter, appFailure)
		return
	}
	id := request.PathValue("id")
	file, err := handler.loadFile(request, id, appCode)
	if err != nil {
		handler.fail(responseWriter, notFound("file not found"))
		return
	}
	diskPath, pathErr := handler.storedFilePath(file.StoredName)
	if pathErr != nil {
		// 库中路径不可能由上传流程产生；不碰 FilesDir 之外的任何路径，仅清理元数据。
		handler.logger().LogAttrs(request.Context(), slog.LevelError, "refuse to delete unsafe stored path",
			slog.String("stored_name", file.StoredName))
	} else if err := os.Remove(diskPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		handler.failFiles(responseWriter, request, "delete stored file", err)
		return
	}
	if _, err := handler.store.DB.ExecContext(request.Context(),
		"DELETE FROM files WHERE id = ? AND app_code = ?", id, appCode); err != nil {
		handler.failFiles(responseWriter, request, "delete file metadata", err)
		return
	}
	writeNoContent(responseWriter)
}

// loadFile 按 id 取元数据；无行时返回 sql.ErrNoRows（调用方统一映射 404）。
func (handler *Handler) loadFile(request *http.Request, id, appCode string) (fileMetadata, error) {
	row := handler.store.DB.QueryRowContext(request.Context(),
		"SELECT "+fileColumns+" FROM files WHERE id = ? AND app_code = ?", id, appCode)
	return scanFileMetadata(row.Scan)
}

func scanFileMetadata(scan func(...any) error) (fileMetadata, error) {
	var file fileMetadata
	err := scan(&file.ID, &file.AppCode, &file.OriginalName, &file.StoredName, &file.MimeType, &file.SizeBytes,
		&file.StoragePath, &file.HashSHA256, &file.UploadedBy, &file.CreatedAt)
	return file, err
}

// storedFilePath 把 stored_name 安全地拼进 FilesDir；拒绝绝对路径与路径分隔符。
func (handler *Handler) storedFilePath(storedName string) (string, error) {
	if handler.store.FilesDir == "" {
		return "", errors.New("files directory is not configured")
	}
	if storedName == "" || storedName == "." || storedName == ".." ||
		filepath.Base(storedName) != storedName || strings.ContainsAny(storedName, "/\\") ||
		filepath.IsAbs(storedName) {
		return "", errInvalidStoredName
	}
	return filepath.Join(handler.store.FilesDir, storedName), nil
}

// contentDisposition 复刻 Nexus 的 attachment 头：
// attachment; filename="<safe_filename>"; filename*=UTF-8”<rfc5987>。
// safe_filename 只含可见 ASCII 且不含双引号/反斜杠，因此 %q 与手写双引号等价。
func contentDisposition(name string) string {
	return fmt.Sprintf("attachment; filename=%q; filename*=UTF-8''%s", safeFilename(name), rfc5987Encode(name))
}

// safeFilename 只保留可见 ASCII 且不含双引号与反斜杠的字符，其余逐字符替换为 _。
func safeFilename(name string) string {
	var safe strings.Builder
	safe.Grow(len(name))
	for _, character := range name {
		if character >= 0x21 && character <= 0x7e && character != '"' && character != '\\' {
			safe.WriteRune(character)
			continue
		}
		safe.WriteByte('_')
	}
	return safe.String()
}

// rfc5987Encode 按 RFC 5987 对 UTF-8 字节做百分号编码（unreserved 字符原样保留）。
func rfc5987Encode(name string) string {
	var encoded strings.Builder
	encoded.Grow(len(name) * 3)
	const hexDigits = "0123456789ABCDEF"
	for _, byteValue := range []byte(name) {
		if isRFC5987Unreserved(byteValue) {
			encoded.WriteByte(byteValue)
			continue
		}
		encoded.WriteByte('%')
		encoded.WriteByte(hexDigits[byteValue>>4])
		encoded.WriteByte(hexDigits[byteValue&0x0f])
	}
	return encoded.String()
}

func isRFC5987Unreserved(byteValue byte) bool {
	if byteValue >= 'a' && byteValue <= 'z' ||
		byteValue >= 'A' && byteValue <= 'Z' ||
		byteValue >= '0' && byteValue <= '9' {
		return true
	}
	switch byteValue {
	case '!', '#', '$', '&', '+', '-', '.', '^', '_', '`', '|', '~':
		return true
	default:
		return false
	}
}

// isValidHeaderValue 判断字符串能否作为 HTTP 头值（可见 ASCII + tab），
// 与 Rust HeaderValue::from_str 的可接受集合一致。
func isValidHeaderValue(value string) bool {
	for index := 0; index < len(value); index++ {
		byteValue := value[index]
		if byteValue == '\t' || (byteValue >= 0x20 && byteValue <= 0x7e) {
			continue
		}
		return false
	}
	return true
}

// isBodyTooLarge 判断错误是否来自 MaxBytesReader 的上限。
func isBodyTooLarge(err error) bool {
	var limitError *http.MaxBytesError
	return errors.As(err, &limitError)
}

// multipartReadError 把 multipart 读取错误映射为 Problem Details。
func multipartReadError(err error) apiError {
	if isBodyTooLarge(err) {
		return payloadTooLarge()
	}
	return badRequest("invalid multipart form data")
}

// logFilesError 记录共享 files 模块的内部错误。
func (handler *Handler) logFilesError(request *http.Request, operation string, err error) {
	handler.logger().LogAttrs(request.Context(), slog.LevelError, "shared files operation failed",
		slog.String("operation", operation), slog.String("error", err.Error()))
}

// failFiles 记录内部错误并写 500。
func (handler *Handler) failFiles(responseWriter http.ResponseWriter, request *http.Request, operation string, err error) {
	handler.logFilesError(request, operation, err)
	handler.fail(responseWriter, internalError())
}
