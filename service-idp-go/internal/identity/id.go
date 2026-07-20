package identity

import (
	"crypto/rand"
	"fmt"
	"time"
)

const crockfordBase32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

func NewULID(now time.Time) (string, error) {
	if now.UnixMilli() < 0 || now.UnixMilli() > (1<<48)-1 {
		return "", fmt.Errorf("timestamp cannot be represented by ULID")
	}

	var value [16]byte
	timestamp := uint64(now.UnixMilli())
	value[0] = byte(timestamp >> 40)
	value[1] = byte(timestamp >> 32)
	value[2] = byte(timestamp >> 24)
	value[3] = byte(timestamp >> 16)
	value[4] = byte(timestamp >> 8)
	value[5] = byte(timestamp)
	if _, err := rand.Read(value[6:]); err != nil {
		return "", fmt.Errorf("read ULID entropy: %w", err)
	}

	return encodeULID(value), nil
}

func encodeULID(value [16]byte) string {
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
