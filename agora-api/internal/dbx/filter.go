// Package dbx holds the small query-building helpers shared by the domain
// stores. It is deliberately narrow: only equality, set membership and
// case-insensitive search, which is all the listings need.
package dbx

import (
	"fmt"
	"strings"
)

// Conditions accumulates SQL predicates with positional placeholders.
type Conditions struct {
	clauses []string
	args    []any
}

// EQ adds "column = value" when value is not empty.
func (c *Conditions) EQ(column, value string) {
	if strings.TrimSpace(value) == "" {
		return
	}
	c.args = append(c.args, value)
	c.clauses = append(c.clauses, fmt.Sprintf("%s = $%d", column, len(c.args)))
}

// ILIKE adds a case-insensitive OR search across columns.
func (c *Conditions) ILIKE(columns []string, value string) {
	value = strings.TrimSpace(value)
	if value == "" || len(columns) == 0 {
		return
	}
	c.args = append(c.args, "%"+value+"%")
	placeholder := fmt.Sprintf("$%d", len(c.args))

	parts := make([]string, 0, len(columns))
	for _, column := range columns {
		parts = append(parts, fmt.Sprintf("%s ILIKE %s", column, placeholder))
	}
	c.clauses = append(c.clauses, "("+strings.Join(parts, " OR ")+")")
}

// Raw adds a literal predicate, typically a status or expiry guard.
func (c *Conditions) Raw(clause string) {
	if strings.TrimSpace(clause) == "" {
		return
	}
	c.clauses = append(c.clauses, clause)
}

// Where renders the WHERE clause including the keyword, or an empty string.
func (c *Conditions) Where() string {
	if len(c.clauses) == 0 {
		return ""
	}
	return " WHERE " + strings.Join(c.clauses, " AND ")
}

// Args exposes the accumulated arguments in placeholder order.
func (c *Conditions) Args() []any {
	return c.args
}

// Add appends one argument and returns its placeholder.
func (c *Conditions) Add(value any) string {
	c.args = append(c.args, value)
	return fmt.Sprintf("$%d", len(c.args))
}
