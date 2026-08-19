// Package ulid provides a minimal standard-library ULID implementation
// (https://github.com/ulid/spec): 128 bits — a 48-bit millisecond
// timestamp followed by 80 bits of cryptographic randomness — encoded as
// 26 characters of Crockford Base32. It exists so prototyped can mint
// server-side ids without pulling in an external dependency.
package ulid

import (
	"crypto/rand"
	"time"
)

// crockford is the Crockford Base32 alphabet: digits 0-9 and the letters
// A-Z with I, L, O and U removed to avoid ambiguity.
const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// New returns a new ULID as a 26-character Crockford Base32 string.
func New() string {
	var raw [16]byte
	timestamp := uint64(time.Now().UnixMilli())
	raw[0] = byte(timestamp >> 40)
	raw[1] = byte(timestamp >> 32)
	raw[2] = byte(timestamp >> 24)
	raw[3] = byte(timestamp >> 16)
	raw[4] = byte(timestamp >> 8)
	raw[5] = byte(timestamp)
	if _, err := rand.Read(raw[6:]); err != nil {
		// A crypto/rand failure leaves no safe fallback for identifier
		// generation; surface it loudly instead of minting weak ids.
		panic("ulid: read crypto/rand: " + err.Error())
	}
	return encode(raw[:])
}

// encode renders 128 bits as 26 Crockford Base32 characters (130 bits; the
// final character carries two zero padding bits).
func encode(raw []byte) string {
	var out [26]byte
	position := 0
	bits := 0
	var accumulator uint64
	for _, b := range raw {
		accumulator = accumulator<<8 | uint64(b)
		bits += 8
		for bits >= 5 {
			bits -= 5
			out[position] = crockford[(accumulator>>bits)&0x1F]
			position++
		}
		accumulator &= (1 << bits) - 1
	}
	if bits > 0 {
		out[position] = crockford[(accumulator<<(5-bits))&0x1F]
		position++
	}
	return string(out[:])
}
