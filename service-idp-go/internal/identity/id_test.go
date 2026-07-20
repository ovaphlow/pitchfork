package identity

import (
	"strings"
	"testing"
	"time"
)

func TestNewULIDProducesCrockfordBase32(t *testing.T) {
	identifier, err := NewULID(time.Date(2026, 7, 20, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("new ULID: %v", err)
	}
	if len(identifier) != 26 {
		t.Fatalf("ULID length = %d, want 26", len(identifier))
	}
	for _, character := range identifier {
		if !strings.ContainsRune(crockfordBase32, character) {
			t.Fatalf("ULID contains invalid character %q", character)
		}
	}
}
