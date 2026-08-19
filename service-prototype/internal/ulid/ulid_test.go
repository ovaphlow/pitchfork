package ulid

import (
	"strings"
	"testing"
	"time"
)

// TestNewFormatAndUniqueness checks the id shape: 26 Crockford Base32
// characters, no duplicates across a large batch.
func TestNewFormatAndUniqueness(t *testing.T) {
	seen := make(map[string]bool, 1000)
	for i := 0; i < 1000; i++ {
		id := New()
		if len(id) != 26 {
			t.Fatalf("id %q: length = %d, want 26", id, len(id))
		}
		for _, r := range id {
			if !strings.ContainsRune(crockford, r) {
				t.Fatalf("id %q contains character %q outside the Crockford alphabet", id, r)
			}
		}
		if seen[id] {
			t.Fatalf("duplicate id %q", id)
		}
		seen[id] = true
	}
}

// TestNewEncodesMillisecondTimestamp decodes the leading 10 characters
// (50 bits: the 48-bit timestamp plus two entropy bits) and verifies the
// timestamp is close to the current wall clock.
func TestNewEncodesMillisecondTimestamp(t *testing.T) {
	before := time.Now().UnixMilli()
	id := New()
	after := time.Now().UnixMilli()

	var value uint64
	for _, r := range id[:10] {
		value = value<<5 | uint64(strings.IndexRune(crockford, r))
	}
	timestamp := value >> 2 // drop the two entropy bits
	if timestamp < uint64(before) || timestamp > uint64(after) {
		t.Fatalf("encoded timestamp = %d, want within [%d, %d]", timestamp, before, after)
	}
}
