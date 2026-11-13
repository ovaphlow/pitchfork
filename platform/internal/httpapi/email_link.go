package httpapi

import (
	"errors"
	"net/http"

	"github.com/ovaphlow/pitchfork/platform/internal/identity"
)

type emailLoginLinkRequest struct {
	Email       string `json:"email"`
	DisplayName string `json:"display_name"`
	RedirectURI string `json:"redirect_uri"`
}

type emailLoginLinkAccepted struct {
	Status string `json:"status"`
}

const emailLoginLinkInvalidDetail = "invalid or expired email login link"

// requestEmailLoginLink 是公开的申请入口：永远不透露邮箱是否已注册。
// 成功、未知邮箱、限流、投递失败一律 202；只有请求本身或配置问题才给出错误。
func (handler Handler) requestEmailLoginLink(responseWriter http.ResponseWriter, request *http.Request) {
	setNoStore(responseWriter)

	var input emailLoginLinkRequest
	if err := decodeJSON(request, responseWriter, &input); err != nil {
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid email login link request")
		return
	}

	err := identity.RequestEmailLoginLink(request.Context(), handler.database, identity.RequestEmailLoginLinkInput{
		Email:         input.Email,
		DisplayName:   input.DisplayName,
		RedirectURI:   input.RedirectURI,
		SourceAddress: clientSourceAddress(request, handler.trustedProxyPrefixes),
	}, handler.emailLoginLinkSettings, handler.loginThrottle.Secret, handler.emailLoginLinkMailer)

	switch {
	case err == nil:
		writeJSON(responseWriter, http.StatusAccepted, emailLoginLinkAccepted{Status: "accepted"})
	case errors.Is(err, identity.ErrInvalidEmailLoginLinkRequest):
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid email login link request")
	case errors.Is(err, identity.ErrEmailLoginLinkRedirectURI):
		// 配置漂移（Agora 与 platformd 的白名单不一致）：运维可见，不记录邮箱。
		handler.logger.Error("email login redirect URI is not allowed", "error", err)
		writeProblem(responseWriter, request, http.StatusInternalServerError, "email-login-misconfigured", "email login redirect URI is not allowed")
	case errors.Is(err, identity.ErrEmailLoginLinkUnavailable):
		writeProblem(responseWriter, request, http.StatusServiceUnavailable, "email-login-unavailable", "email login is not configured")
	case errors.Is(err, identity.ErrEmailLoginLinkDelivery):
		// 投递失败仍然 202：对外不区分"没注册"与"没发出去"。
		handler.logger.Warn("email login link delivery failed", "error", err)
		writeJSON(responseWriter, http.StatusAccepted, emailLoginLinkAccepted{Status: "accepted"})
	default:
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not create email login link")
	}
}

// exchangeEmailLoginLink 消费一次性 token 并直接签发完整会话。
// 不解析请求体：token 位于路径，兑换动作是幂等意义上的"点击"。
func (handler Handler) exchangeEmailLoginLink(responseWriter http.ResponseWriter, request *http.Request) {
	setNoStore(responseWriter)

	token := request.PathValue("token")
	if token == "" || len(token) > 128 {
		writeProblem(responseWriter, request, http.StatusUnauthorized, "invalid-email-login-link", emailLoginLinkInvalidDetail)
		return
	}

	login, err := identity.ConsumeEmailLoginLink(
		request.Context(),
		handler.database,
		token,
		handler.sessionSettings,
		handler.loginThrottle.Secret,
		clientSourceAddress(request, handler.trustedProxyPrefixes),
	)
	if err != nil {
		if errors.Is(err, identity.ErrInvalidEmailLoginLink) {
			writeProblem(responseWriter, request, http.StatusUnauthorized, "invalid-email-login-link", emailLoginLinkInvalidDetail)
			return
		}
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not exchange email login link")
		return
	}

	handler.setSessionCookies(responseWriter, login)
	writeJSON(responseWriter, http.StatusOK, map[string]string{"access": login.Access})
}

// setNoStore 阻止浏览器与中间层缓存登录链接相关的响应。
func setNoStore(responseWriter http.ResponseWriter) {
	responseWriter.Header().Set("Cache-Control", "no-store")
	responseWriter.Header().Set("Pragma", "no-cache")
}
