package id

import (
	"strings"
	"testing"
	"time"
)

func TestEncodeKnownVectors(t *testing.T) {
	allZero := encode([16]byte{})
	if allZero != "00000000000000000000000000" {
		t.Fatalf("encode(zero) = %q", allZero)
	}

	allOnes := [16]byte{}
	for index := range allOnes {
		allOnes[index] = 0xFF
	}
	encoded := encode(allOnes)
	if encoded != "7ZZZZZZZZZZZZZZZZZZZZZZZZZ" {
		t.Fatalf("encode(ones) = %q", encoded)
	}
}

func TestNewIsCrockfordBase32(t *testing.T) {
	value, err := New(time.UnixMilli(1_700_000_000_000))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if len(value) != 26 {
		t.Fatalf("length = %d, want 26", len(value))
	}
	for _, r := range value {
		if !strings.ContainsRune(crockfordBase32, r) {
			t.Fatalf("value %q contains %q outside Crockford Base32", value, r)
		}
	}
	// Crockford excludes I, L, O and U so they cannot be confused with 1/0.
	if strings.ContainsAny(value, "ILOU") {
		t.Fatalf("value %q contains an excluded letter", value)
	}
}

func TestNewIsSortableAndUnique(t *testing.T) {
	earlier, err := New(time.UnixMilli(1_700_000_000_000))
	if err != nil {
		t.Fatal(err)
	}
	later, err := New(time.UnixMilli(1_700_000_001_000))
	if err != nil {
		t.Fatal(err)
	}
	if !(earlier < later) {
		t.Fatalf("%q should sort before %q", earlier, later)
	}

	first, err := New(time.UnixMilli(1_700_000_000_000))
	if err != nil {
		t.Fatal(err)
	}
	second, err := New(time.UnixMilli(1_700_000_000_000))
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("two ULIDs generated in the same millisecond must differ")
	}
}

func TestNewRejectsOutOfRangeTimestamps(t *testing.T) {
	if _, err := New(time.UnixMilli(-1)); err == nil {
		t.Fatal("expected an error for a negative timestamp")
	}
	if _, err := New(time.UnixMilli(1 << 48)); err == nil {
		t.Fatal("expected an error beyond the 48-bit range")
	}
}
