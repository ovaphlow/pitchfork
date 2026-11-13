// Package id generates the 26-character Crockford Base32 ULIDs used as primary
// keys. The encoding matches platformd so identifiers stay comparable
// across services without sharing a dependency.
package id

import (
	"crypto/rand"
	"fmt"
	"time"
)

const crockfordBase32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// New returns a ULID for the given instant.
func New(now time.Time) (string, error) {
	milliseconds := now.UnixMilli()
	if milliseconds < 0 || milliseconds > (1<<48)-1 {
		return "", fmt.Errorf("timestamp cannot be represented by ULID")
	}

	var value [16]byte
	timestamp := uint64(milliseconds)
	value[0] = byte(timestamp >> 40)
	value[1] = byte(timestamp >> 32)
	value[2] = byte(timestamp >> 24)
	value[3] = byte(timestamp >> 16)
	value[4] = byte(timestamp >> 8)
	value[5] = byte(timestamp)
	if _, err := rand.Read(value[6:]); err != nil {
		return "", fmt.Errorf("read ULID entropy: %w", err)
	}
	return encode(value), nil
}

func encode(value [16]byte) string {
	encoded := make([]byte, 26)
	for outputIndex := range encoded {
		group := byte(0)
		for bitOffset := 0; bitOffset < 5; bitOffset++ {
			sourceBit := outputIndex*5 + bitOffset - 2
			group <<= 1
			if sourceBit < 0 || sourceBit >= 128 {
				continue
			}
			byteIndex := sourceBit / 8
			bitIndex := 7 - (sourceBit % 8)
			group |= (value[byteIndex] >> bitIndex) & 1
		}
		encoded[outputIndex] = crockfordBase32[group]
	}
	return string(encoded)
}
