package com.quantfolio.test

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.fail

/** 원본 Python 이 만든 정답지(test resources 의 oracle 디렉터리 JSON) 로더와 근사 비교. */
object Oracle {
    private val json = JsonMapper.builder().build()

    fun load(name: String): JsonNode {
        val stream = Oracle::class.java.getResourceAsStream("/oracle/$name.json") ?: error("정답지 없음: $name.json")
        return stream.use { json.readTree(it) }
    }

    /** "NaN"/"Infinity" 문자열을 복원한다. */
    fun num(node: JsonNode?): Double = when {
        node == null || node.isNull -> Double.NaN
        node.isString -> when (node.asString()) {
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> node.asString().toDouble()
        }
        else -> node.asDouble()
    }

    fun doubles(node: JsonNode): DoubleArray = DoubleArray(node.size()) { num(node.get(it)) }

    /** 상대 오차 rel(작은 값은 절대 오차 1e-12 바닥). NaN 위치도 같아야 한다. */
    fun assertClose(expected: DoubleArray, actual: DoubleArray, rel: Double = 1e-9, label: String = "") {
        if (expected.size != actual.size) fail("$label 길이 다름: ${expected.size} vs ${actual.size}")
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            if (e.isNaN() || a.isNaN()) {
                if (e.isNaN() != a.isNaN()) fail("$label [$i] NaN 불일치: expected=$e actual=$a")
                continue
            }
            if (e.isInfinite() || a.isInfinite()) {
                if (e != a) fail("$label [$i] expected=$e actual=$a")
                continue
            }
            if (abs(e - a) > max(1e-12, rel * abs(e))) fail("$label [$i] expected=$e actual=$a")
        }
    }
}
