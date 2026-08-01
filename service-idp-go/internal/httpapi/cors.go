package httpapi

import "net/http"

const (
	accessControlAllowCredentials = "true"
	accessControlAllowMethods     = "GET, POST, PUT, PATCH, DELETE, OPTIONS"
	accessControlAllowHeaders     = "Accept, Content-Type, Authorization, X-CSRF-Token"
)

type corsHandler struct {
	next           http.Handler
	allowedOrigins []string
}

func (handler corsHandler) ServeHTTP(responseWriter http.ResponseWriter, request *http.Request) {
	origin := request.Header.Get("Origin")
	if origin == "" || !containsOrigin(handler.allowedOrigins, origin) {
		handler.next.ServeHTTP(responseWriter, request)
		return
	}

	responseWriter.Header().Add("Vary", "Origin")
	responseWriter.Header().Set("Access-Control-Allow-Origin", origin)
	responseWriter.Header().Set("Access-Control-Allow-Credentials", accessControlAllowCredentials)
	if request.Method == http.MethodOptions {
		responseWriter.Header().Set("Access-Control-Allow-Methods", accessControlAllowMethods)
		responseWriter.Header().Set("Access-Control-Allow-Headers", accessControlAllowHeaders)
		responseWriter.WriteHeader(http.StatusNoContent)
		return
	}

	handler.next.ServeHTTP(responseWriter, request)
}

func containsOrigin(origins []string, target string) bool {
	for _, origin := range origins {
		if origin == target {
			return true
		}
	}
	return false
}
