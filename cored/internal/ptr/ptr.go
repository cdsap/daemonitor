// Package ptr provides pointers to optional metric and metadata values.
package ptr

func Int64(value int64) *int64       { return &value }
func Float64(value float64) *float64 { return &value }
func String(value string) *string    { return &value }
