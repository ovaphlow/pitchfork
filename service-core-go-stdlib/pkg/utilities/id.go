package utilities

import (
	"os"
	"strconv"

	"github.com/bwmarrin/snowflake"
	"github.com/segmentio/ksuid"
)

// NewKSUID generates a new globally unique KSUID string.
func NewKSUID() string {
	return ksuid.New().String()
}

// NewSnowflakeID generates a snowflake ID string using a node ID from
// the environment variable SNOWFLAKE_NODE. If node setup fails it falls
// back to generating a KSUID string to ensure a unique ID is returned.
func NewSnowflakeID() string {
	nodeEnv := os.Getenv("SNOWFLAKE_NODE")
	if nodeEnv == "" {
		// default to node 1 when not provided so snowflake IDs are still produced
		return NewSnowflakeIDWithNode(1)
	}
	nodeID, err := strconv.ParseInt(nodeEnv, 10, 64)
	if err != nil {
		// fall back to a default node instead of KSUID
		return NewSnowflakeIDWithNode(1)
	}
	return NewSnowflakeIDWithNode(nodeID)
}

// NewSnowflakeIDWithNode generates a snowflake ID string using the provided node ID.
// If the node cannot be initialized, it falls back to a KSUID string.
func NewSnowflakeIDWithNode(nodeID int64) string {
	node, err := snowflake.NewNode(nodeID)
	if err != nil {
		return NewKSUID()
	}
	return node.Generate().String()
}
