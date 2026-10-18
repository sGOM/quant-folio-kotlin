package com.quantfolio.security

import org.springframework.security.crypto.bcrypt.BCrypt

/** bcrypt 비밀번호 해시. 원본 Python bcrypt 의 `$2b$` 해시도 검증된다. */
object Passwords {
    fun hash(raw: String): String = BCrypt.hashpw(raw, BCrypt.gensalt())

    fun verify(raw: String, hash: String): Boolean = runCatching { BCrypt.checkpw(raw, hash) }.getOrDefault(false)
}
