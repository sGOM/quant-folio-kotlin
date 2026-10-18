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

    /**
     * JSON 정답과 Kotlin 값(Map·List·Number·String·Boolean·null)을 재귀 비교한다. 숫자는 [assertClose] 규칙,
     * 맵은 키 집합까지 같아야 한다. 정답의 정수와 Kotlin 실수(또는 반대)는 값만 같으면 통과.
     */
    fun assertDeep(expected: JsonNode?, actual: Any?, rel: Double = 1e-9, path: String = "$") {
        when {
            expected == null || expected.isNull -> if (actual != null && !(actual is Double && actual.isNaN())) fail("$path expected=null actual=$actual")
            expected.isObject -> {
                val m = actual as? Map<*, *> ?: fail("$path 맵이어야 함: $actual")
                val keys = expected.propertyNames().toSet()
                if (keys != m.keys.map { it.toString() }.toSet()) fail("$path 키 다름: expected=$keys actual=${m.keys}")
                for (k in keys) assertDeep(expected.get(k), m[k], rel, "$path.$k")
            }
            expected.isArray -> {
                val l = (actual as? List<*>) ?: (actual as? DoubleArray)?.toList() ?: fail("$path 리스트여야 함: $actual")
                if (expected.size() != l.size) fail("$path 길이 다름: ${expected.size()} vs ${l.size}")
                for (i in 0 until expected.size()) assertDeep(expected.get(i), l[i], rel, "$path[$i]")
            }
            expected.isBoolean -> if (expected.asBoolean() != actual) fail("$path expected=${expected.asBoolean()} actual=$actual")
            expected.isNumber || (expected.isString && expected.asString() in setOf("NaN", "Infinity", "-Infinity")) -> {
                val a = (actual as? Number)?.toDouble() ?: fail("$path 숫자여야 함: $actual")
                assertClose(doubleArrayOf(num(expected)), doubleArrayOf(a), rel, path)
            }
            else -> if (expected.asString() != actual?.toString()) fail("$path expected=${expected.asString()} actual=$actual")
        }
    }
}
