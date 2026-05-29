package com.ovaphlow.crate.common

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

object RsaCrypto {

    private const val ALGORITHM = "RSA"
    /** jsencrypt 默认使用 RSA/ECB/PKCS1Padding */
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val KEY_SIZE = 2048

    data class KeyPairHolder(val publicKey: PublicKey, val privateKey: PrivateKey)

    /** 生成 RSA 密钥对 */
    fun generateKeyPair(): KeyPairHolder {
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        generator.initialize(KEY_SIZE)
        val pair: KeyPair = generator.generateKeyPair()
        return KeyPairHolder(pair.public, pair.private)
    }

    /** 将公钥编码为 Base64 (X.509 SubjectPublicKeyInfo) */
    fun encodePublicKeyBase64(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(publicKey.encoded)

    /** 从 Base64 解码公钥 */
    fun decodePublicKeyBase64(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded)
        return KeyFactory.getInstance(ALGORITHM)
            .generatePublic(X509EncodedKeySpec(bytes))
    }

    /** 将私钥编码为 Base64 (PKCS#8) */
    fun encodePrivateKeyBase64(privateKey: PrivateKey): String =
        Base64.getEncoder().encodeToString(privateKey.encoded)

    /** 从 Base64 解码私钥 */
    fun decodePrivateKeyBase64(encoded: String): PrivateKey {
        val bytes = Base64.getDecoder().decode(encoded)
        return KeyFactory.getInstance(ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    /** 用私钥解密 Base64 编码的密文（PKCS#1 v1.5 padding，与 jsencrypt 兼容） */
    fun decrypt(encryptedBase64: String, privateKey: PrivateKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedBase64))
        return String(decrypted, Charsets.UTF_8)
    }
}