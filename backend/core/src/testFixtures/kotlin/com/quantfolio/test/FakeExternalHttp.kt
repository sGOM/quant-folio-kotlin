package com.quantfolio.test

import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.source.HttpBytes
import java.io.IOException
import java.time.Duration

/** 요청을 기록하고 조건에 맞는 응답을 돌려주는 가짜 HTTP. 나중에 등록한 핸들러가 우선. */
class FakeExternalHttp : ExternalHttp {
    data class Call(val method: String, val url: String, val query: Map<String, String>, val body: String?, val headers: Map<String, String>)

    val calls = mutableListOf<Call>()
    private val handlers = mutableListOf<Pair<(Call) -> Boolean, (Call) -> HttpBytes>>()

    fun on(match: (Call) -> Boolean, respond: (Call) -> HttpBytes) {
        handlers.add(0, match to respond)
    }

    fun onText(match: (Call) -> Boolean, status: Int = 200, body: String) = on(match) { HttpBytes(status, body.toByteArray()) }

    fun failWith(match: (Call) -> Boolean) = on(match) { throw IOException("connection refused") }

    override fun get(url: String, query: Map<String, String>, headers: Map<String, String>, timeout: Duration): HttpBytes =
        handle(Call("GET", url, query, null, headers))

    override fun postJson(url: String, json: String, headers: Map<String, String>, timeout: Duration): HttpBytes =
        handle(Call("POST", url, emptyMap(), json, headers))

    private fun handle(call: Call): HttpBytes {
        calls += call
        val handler = handlers.firstOrNull { it.first(call) } ?: return HttpBytes(404, ByteArray(0))
        return handler.second(call)
    }
}
