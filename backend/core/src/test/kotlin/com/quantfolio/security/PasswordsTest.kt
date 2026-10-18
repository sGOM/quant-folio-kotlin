package com.quantfolio.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordsTest {
    @Test
    fun verifiesPythonBcrypt() {
        // 원본 Python bcrypt 가 만든 $2b$ 해시 — 기존 사용자가 그대로 로그인할 수 있어야 한다.
        assertTrue(Passwords.verify("correct horse 8", "\$2b\$12\$tzU.mBUX9gtZUw3/q0ZafehPvEa.xtjVjhvJqfSPzzETSeaPxJC3y"))
    }

    @Test
    fun roundTrip() {
        assertTrue(Passwords.verify("pw-12345", Passwords.hash("pw-12345")))
    }

    @Test
    fun wrongPasswordFalse() {
        assertFalse(Passwords.verify("nope", Passwords.hash("pw-12345")))
    }

    @Test
    fun malformedHashFalse() {
        assertFalse(Passwords.verify("pw", "not-a-hash"))
    }
}
