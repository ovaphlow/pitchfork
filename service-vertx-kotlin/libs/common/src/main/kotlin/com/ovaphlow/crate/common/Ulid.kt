package com.ovaphlow.crate.common

import java.math.BigInteger
import java.security.SecureRandom
import java.time.Instant

object Ulid {

    private val encoding = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String {
        val time = Instant.now().toEpochMilli()
        val bytes = ByteArray(10)
        random.nextBytes(bytes)
        return encodeTime(time) + encodeRandom(bytes)
    }

    private fun encodeTime(time: Long): String {
        val chars = CharArray(10)
        var t = time
        for (i in 9 downTo 0) {
            chars[i] = encoding[(t % 32).toInt()]
            t /= 32
        }
        return String(chars)
    }

    private fun encodeRandom(bytes: ByteArray): String {
        val num = BigInteger(1, bytes)
        val base = BigInteger.valueOf(32)
        val chars = CharArray(16)
        var n = num
        for (i in 15 downTo 0) {
            chars[i] = encoding[n.mod(base).toInt()]
            n = n.divide(base)
        }
        return String(chars)
    }
}
