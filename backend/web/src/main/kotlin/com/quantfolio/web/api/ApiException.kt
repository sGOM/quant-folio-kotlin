package com.quantfolio.web.api

import org.springframework.http.HttpStatus

/** 라우트가 던지는 HTTP 오류. 응답은 `{"detail": detail}` (원본 FastAPI HTTPException 과 같은 형태). */
open class ApiException(val status: HttpStatus, val detail: String) : RuntimeException(detail)
