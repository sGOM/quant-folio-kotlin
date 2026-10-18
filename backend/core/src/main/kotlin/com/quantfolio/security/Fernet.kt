package com.quantfolio.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class InvalidFernetToken(message: String) : RuntimeException(message)

/**
 * Python `cryptography.fernet` 과 바이트 단위로 호환되는 Fernet 구현.
 * 토큰 = base64url(0x80 ‖ 발급시각(8B) ‖ IV(16B) ‖ AES-128-CBC 암호문 ‖ HMAC-SHA256(앞 전체)).
 * 키(base64url 32B)의 앞 16B 가 서명키, 뒤 16B 가 암호키. 원본처럼 TTL 은 검사하지 않는다.
 */
class Fernet(key: String) {
    private val signingKey: ByteArray
    private val encryptionKey: ByteArray

    init {
        val raw = runCatching { Base64.getUrlDecoder().decode(key) }.getOrNull()
        require(raw != null && raw.size == 32) { "Fernet 키는 base64url 32바이트여야 합니다." }
        signingKey = raw.copyOfRange(0, 16)
        encryptionKey = raw.copyOfRange(16, 32)
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val body = ByteBuffer.allocate(1 + 8 + 16 + ciphertext.size)
            .put(VERSION).putLong(Instant.now().epochSecond).put(iv).put(ciphertext).array()
        return Base64.getUrlEncoder().encodeToString(body + hmac(body))
    }

    fun decrypt(token: String): String {
        val data = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()
            ?: throw InvalidFernetToken("base64 형식이 아닙니다.")
        if (data.size < 1 + 8 + 16 + 16 + 32 || data[0] != VERSION) throw InvalidFernetToken("토큰 형식이 올바르지 않습니다.")
        val body = data.copyOfRange(0, data.size - 32)
        if (!MessageDigest.isEqual(hmac(body), data.copyOfRange(data.size - 32, data.size))) {
            throw InvalidFernetToken("서명이 일치하지 않습니다(키 불일치 또는 위변조).")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(body.copyOfRange(9, 25)))
        val plain = runCatching { cipher.doFinal(body.copyOfRange(25, body.size)) }
            .getOrElse { throw InvalidFernetToken("복호화에 실패했습니다.") }
        return String(plain, Charsets.UTF_8)
    }

    private fun hmac(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(signingKey, "HmacSHA256")) }.doFinal(data)

    companion object {
        private const val VERSION: Byte = 0x80.toByte()
        private val random = SecureRandom()

        fun generateKey(): String = Base64.getUrlEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
    }
}
