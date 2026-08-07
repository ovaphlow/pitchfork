package com.ovaphlow.crate.pharmacy

class NotFoundException(message: String) : Exception(message)

/** 业务冲突：重复接方、状态非法跳转、库存不足、医嘱已停嘱等，路由映射为 409 */
class ConflictException(message: String) : Exception(message)
