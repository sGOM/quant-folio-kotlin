package com.quantfolio.test

import com.quantfolio.market.krx.HttpResult
import com.quantfolio.market.krx.KrxTransport
import java.io.IOException
import java.time.Duration

/** 요청을 기록하고, URL·form 에 맞는 응답을 순서대로 돌려주는 가짜 transport. */
class FakeKrxTransport : KrxTransport {
    data class Call(val method: String, val url: String, val form: Map<String, String>, val headers: Map<String, String>)

    val calls = mutableListOf<Call>()
    private val handlers = mutableListOf<Pair<(Call) -> Boolean, (Call) -> HttpResult>>()
    var cookieClears = 0

    /** 조건에 맞는 요청에 응답한다. 나중에 등록한 것이 우선. */
    fun on(match: (Call) -> Boolean, respond: (Call) -> HttpResult) {
        handlers.add(0, match to respond)
    }

    fun onBld(bld: String, json: String) = on({ it.form["bld"] == bld }) { HttpResult(200, json) }

    fun failWith(match: (Call) -> Boolean) = on(match) { throw IOException("connection refused") }

    override fun get(url: String, headers: Map<String, String>): HttpResult = handle(Call("GET", url, emptyMap(), headers))

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeout: Duration): HttpResult =
        handle(Call("POST", url, form, headers))

    override fun clearCookies() {
        cookieClears++
    }

    private fun handle(call: Call): HttpResult {
        calls += call
        val handler = handlers.firstOrNull { it.first(call) } ?: return HttpResult(200, "{}")
        return handler.second(call)
    }
}
