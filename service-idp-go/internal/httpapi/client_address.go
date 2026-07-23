package httpapi

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
)

func clientSourceAddress(request *http.Request, trustedProxyPrefixes []netip.Prefix) string {
	remoteAddress, valid := parsedAddress(request.RemoteAddr)
	if !valid {
		return "unknown"
	}
	if !trustedAddress(remoteAddress, trustedProxyPrefixes) {
		return remoteAddress.String()
	}

	forwardedAddresses := strings.Split(request.Header.Get("X-Forwarded-For"), ",")
	for index := len(forwardedAddresses) - 1; index >= 0; index-- {
		address, valid := parsedAddress(forwardedAddresses[index])
		if !valid || trustedAddress(address, trustedProxyPrefixes) {
			continue
		}
		return address.String()
	}
	return remoteAddress.String()
}

func parsedAddress(value string) (netip.Addr, bool) {
	value = strings.TrimSpace(value)
	if host, _, err := net.SplitHostPort(value); err == nil {
		value = host
	}
	address, err := netip.ParseAddr(value)
	if err != nil {
		return netip.Addr{}, false
	}
	return address.Unmap(), true
}

func trustedAddress(address netip.Addr, trustedProxyPrefixes []netip.Prefix) bool {
	for _, prefix := range trustedProxyPrefixes {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}
