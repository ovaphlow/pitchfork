package identity

// ref 返回指向 value 的指针，用于给可空列（映射为 *T）赋值。
// 字面量与常量无法直接取地址，因此需要这个辅助函数。
func ref[T any](value T) *T {
	return &value
}
