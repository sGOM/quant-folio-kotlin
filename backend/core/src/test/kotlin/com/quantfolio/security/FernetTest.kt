package com.quantfolio.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class FernetTest {
    // 원본 Python cryptography.fernet 으로 만든 픽스처 — 기존 DB 의 증권사 키 복호화 호환.
    private val key = "q8Zx3-1o0ZL3bP4kQ6V0wJm2r9sYH5c7nT1uE2fGhIk="
    private val pythonToken =
        "gAAAAABq1O9Ed_IckAjfAkJiEKvFB6QxHmnNu7dMuWkDxAJ1zIiuDvasSZk0kDdPSJa5MxDL2QV74zFCugb17bxICIEmjFvn6kcWkDZ4wg69-rYII5Cgw10="
    private val plain = "PSabc123-앱시크릿/+="

    @Test
    fun decryptsPythonToken() {
        assertEquals(plain, Fernet(key).decrypt(pythonToken))
    }

    @Test
    fun roundTrip() {
        val f = Fernet(key)
        assertEquals(plain, f.decrypt(f.encrypt(plain)))
    }

    @Test
    fun tamperedTokenRejected() {
        val last = pythonToken[pythonToken.length - 2]
        val tampered = pythonToken.dropLast(2) + (if (last == 'A') 'B' else 'A') + "="
        assertThrows<InvalidFernetToken> { Fernet(key).decrypt(tampered) }
    }

    @Test
    fun wrongKeyRejected() {
        assertThrows<InvalidFernetToken> { Fernet(Fernet.generateKey()).decrypt(pythonToken) }
    }

    @Test
    fun malformedKeyRejected() {
        assertThrows<IllegalArgumentException> { Fernet("not-a-key") }
    }
}
