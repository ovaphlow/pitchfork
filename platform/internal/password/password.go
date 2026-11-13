package password

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

const minimumLength = 12

type Parameters struct {
	Memory      uint32
	Iterations  uint32
	Parallelism uint8
	SaltLength  uint32
	KeyLength   uint32
}

var defaultParameters = Parameters{
	Memory:      19 * 1024,
	Iterations:  2,
	Parallelism: 1,
	SaltLength:  16,
	KeyLength:   32,
}

func Hash(value string) (string, error) {
	if len(value) < minimumLength {
		return "", fmt.Errorf("password must contain at least %d bytes", minimumLength)
	}

	salt := make([]byte, defaultParameters.SaltLength)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("read password salt: %w", err)
	}
	hash := argon2.IDKey([]byte(value), salt, defaultParameters.Iterations, defaultParameters.Memory, defaultParameters.Parallelism, defaultParameters.KeyLength)
	return fmt.Sprintf(
		"$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
		defaultParameters.Memory,
		defaultParameters.Iterations,
		defaultParameters.Parallelism,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	), nil
}

func Verify(value string, encoded string) (bool, error) {
	parameters, salt, expectedHash, err := parse(encoded)
	if err != nil {
		return false, err
	}
	actualHash := argon2.IDKey([]byte(value), salt, parameters.Iterations, parameters.Memory, parameters.Parallelism, uint32(len(expectedHash)))
	return subtle.ConstantTimeCompare(actualHash, expectedHash) == 1, nil
}

func parse(encoded string) (Parameters, []byte, []byte, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" || parts[2] != "v=19" {
		return Parameters{}, nil, nil, fmt.Errorf("invalid Argon2id password hash")
	}

	parameters := Parameters{}
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &parameters.Memory, &parameters.Iterations, &parameters.Parallelism); err != nil {
		return Parameters{}, nil, nil, fmt.Errorf("parse Argon2id parameters: %w", err)
	}
	if parameters.Memory == 0 || parameters.Iterations == 0 || parameters.Parallelism == 0 {
		return Parameters{}, nil, nil, fmt.Errorf("invalid Argon2id parameters")
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil || len(salt) == 0 {
		return Parameters{}, nil, nil, fmt.Errorf("decode Argon2id salt")
	}
	expectedHash, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil || len(expectedHash) == 0 {
		return Parameters{}, nil, nil, fmt.Errorf("decode Argon2id hash")
	}
	return parameters, salt, expectedHash, nil
}
