package httpapi

import (
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
)

func TestClientSourceAddressUsesForwardedAddressOnlyFromTrustedProxy(t *testing.T) {
	trustedProxies := []netip.Prefix{
		netip.MustParsePrefix("127.0.0.1/32"),
		netip.MustParsePrefix("10.0.0.0/8"),
	}
	tests := []struct {
		name         string
		remoteAddr   string
		forwardedFor string
		want         string
	}{
		{name: "direct request ignores forwarded header", remoteAddr: "192.0.2.10:1234", forwardedFor: "198.51.100.20", want: "192.0.2.10"},
		{name: "trusted proxy preserves client address", remoteAddr: "127.0.0.1:1234", forwardedFor: "198.51.100.20", want: "198.51.100.20"},
		{name: "trusted proxy chain skips trusted hop", remoteAddr: "127.0.0.1:1234", forwardedFor: "198.51.100.20, 10.1.2.3", want: "198.51.100.20"},
		{name: "invalid remote address falls back", remoteAddr: "not-an-address", forwardedFor: "198.51.100.20", want: "unknown"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, identityPrefix+"/sessions", nil)
			request.RemoteAddr = test.remoteAddr
			request.Header.Set("X-Forwarded-For", test.forwardedFor)
			if got := clientSourceAddress(request, trustedProxies); got != test.want {
				t.Fatalf("client source address = %q, want %q", got, test.want)
			}
		})
	}
}
