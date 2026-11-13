// Package apierr holds the error kinds shared by the domain stores and the
// HTTP layer, so handlers can map failures onto status codes without importing
// driver-specific types.
package apierr

import (
	"errors"
	"fmt"
)

// Message is the wire-visible text for a rejected request.
const (
	MessageNotFound     = "resource not found"
	MessageConflict     = "resource already exists"
	MessageUnauthorized = "not signed in"
	MessageForbidden    = "not allowed"
)

// Validation marks a client error caused by invalid input.
type Validation struct {
	Message string
}

func (e *Validation) Error() string { return e.Message }

// Invalid builds a Validation error.
func Invalid(format string, args ...any) error {
	return &Validation{Message: fmt.Sprintf(format, args...)}
}

// NotFound reports a missing record.
type NotFound struct {
	Resource string
}

func (e *NotFound) Error() string {
	if e.Resource == "" {
		return MessageNotFound
	}
	return e.Resource + " not found"
}

// Conflict reports a uniqueness or state conflict.
type Conflict struct {
	Message string
}

func (e *Conflict) Error() string {
	if e.Message == "" {
		return MessageConflict
	}
	return e.Message
}

// Forbidden reports an authorization failure for an authenticated subject.
type Forbidden struct {
	Message string
}

func (e *Forbidden) Error() string {
	if e.Message == "" {
		return MessageForbidden
	}
	return e.Message
}

// Unauthorized reports a missing or rejected session.
type Unauthorized struct{}

func (e *Unauthorized) Error() string { return MessageUnauthorized }

// IsNotFound reports whether err is a NotFound error.
func IsNotFound(err error) bool {
	var target *NotFound
	return errors.As(err, &target)
}

// IsConflict reports whether err is a Conflict error.
func IsConflict(err error) bool {
	var target *Conflict
	return errors.As(err, &target)
}

// IsForbidden reports whether err is a Forbidden error.
func IsForbidden(err error) bool {
	var target *Forbidden
	return errors.As(err, &target)
}

// IsUnauthorized reports whether err is an Unauthorized error.
func IsUnauthorized(err error) bool {
	var target *Unauthorized
	return errors.As(err, &target)
}

// ValidationMessage returns the validation text and whether err is a
// Validation error.
func ValidationMessage(err error) (string, bool) {
	var target *Validation
	if errors.As(err, &target) {
		return target.Message, true
	}
	return "", false
}
