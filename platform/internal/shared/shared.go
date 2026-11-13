// Package shared 提供跨产品共享能力（/crate-api/v1/*），
// 契约与已停更的 service-nexus-shared 保持一致（见 docs/plans/055）。
// 认证由 platformd 的完整会话完成，不再跨进程转发 Cookie。
package shared

import (
	"database/sql"
	"log/slog"
	"net/http"
)

// Prefix 是共享模块的根路径，同时用于 Problem Details 的 type 前缀。
const Prefix = "/crate-api/v1"

// Session 是共享模块需要的平台会话最小视图。
type Session struct {
	SubjectID     string
	PlatformAdmin bool
}

// Store 是共享模块的运行依赖。
type Store struct {
	DB             *sql.DB
	FilesDir       string
	MaxUploadBytes int64
	Logger         *slog.Logger
	// RequireSession 解析平台完整会话；未通过时返回 false（生产接线见 httpapi.NewMux）。
	RequireSession func(*http.Request) (Session, bool)
}

// Handler 汇总共享模块的 HTTP 处理器。
type Handler struct {
	store Store
}

// NewHandler 构造共享模块处理器。
func NewHandler(store Store) *Handler {
	if store.Logger == nil {
		store.Logger = slog.Default()
	}
	return &Handler{store: store}
}

// Register 把全部共享路由挂到 mux 上。
func (handler *Handler) Register(mux *http.ServeMux) {
	handler.registerSettings(mux)
	handler.registerMessages(mux)
	handler.registerFiles(mux)
	handler.registerInteractions(mux)
	handler.registerRoles(mux)
	handler.registerSubjectRoles(mux)
	handler.registerDepartments(mux)
	handler.registerSubjectDepartments(mux)
}

// logger 返回模块日志器。
func (handler *Handler) logger() *slog.Logger {
	return handler.store.Logger
}
