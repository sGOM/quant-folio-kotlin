package com.quantfolio.strategy

import java.math.BigDecimal
import java.math.RoundingMode

/** pydantic 과 같은 형식의 검증 오류 한 건(loc 은 판별자 태그부터). */
data class ConfigError(val loc: List<Any>, val msg: String, val type: String)

class StrategyConfigException(val errors: List<ConfigError>) : RuntimeException(errors.joinToString("; ") { "${it.loc}: ${it.msg}" })

/** 검증기 안에서 던지는 사용자 오류 — pydantic 처럼 `Value error, {message}` 로 기록된다. */
class ValueError(message: String) : RuntimeException(message)

/** 필드 타입. */
sealed interface Ty {
    data object IntT : Ty
    data object FloatT : Ty
    data object BoolT : Ty
    data object StrT : Ty
    data class Lit(val options: List<String>) : Ty
    data class ModelT(val model: Model) : Ty
    data class ListT(val item: Ty) : Ty

    /** 조건 노드(그룹 | 조건) — pydantic smart union. */
    data object RuleNodeT : Ty
}

/** 기본값 없음(필수 필드). */
data object Required

/** 기본값이 "그 모델의 빈 입력 dump" 인 필드(pydantic default_factory=Model). */
data object DefaultModel

/**
 * 필드 명세(pydantic Field). 제약 값은 원본에 적힌 그대로(Int/Double)여야 오류 문구의 숫자 표기가 같아진다.
 * [before] 는 타입 검증 전 변환(mode='before'), [after] 는 타입·제약 통과 후 검증·변환(field_validator) — ValueError 를 던질 수 있다.
 */
data class F(
    val name: String,
    val ty: Ty,
    val default: Any? = Required,
    val nullable: Boolean = false,
    val ge: Number? = null,
    val gt: Number? = null,
    val le: Number? = null,
    val minLen: Int? = null,
    val maxLen: Int? = null,
    val before: ((Any?) -> Any?)? = null,
    val after: ((Any?) -> Any?)? = null,
)

/** 모델 명세(pydantic BaseModel). 검증기는 필드가 모두 유효할 때만 돈다(model_validator mode='after'). 맵을 고칠 수 있다. */
class Model(val name: String, val fields: List<F>, val validators: List<(MutableMap<String, Any?>) -> Unit> = emptyList())

/** pydantic v2 lax 모드의 검증 엔진. */
object Validator {
    fun validateModel(model: Model, input: Any?, loc: List<Any>, errors: MutableList<ConfigError>): MutableMap<String, Any?>? {
        if (input !is Map<*, *>) {
            errors += ConfigError(loc, "Input should be a valid dictionary or instance of ${model.name}", "model_type")
            return null
        }
        val before = errors.size
        val out = LinkedHashMap<String, Any?>()
        for (f in model.fields) {
            val floc = loc + f.name
            if (!input.containsKey(f.name)) {
                when (f.default) {
                    Required -> errors += ConfigError(floc, "Field required", "missing")
                    DefaultModel -> out[f.name] = validateModel((f.ty as Ty.ModelT).model, emptyMap<String, Any?>(), floc, errors)
                    else -> out[f.name] = f.default
                }
                continue
            }
            out[f.name] = validateField(f, input[f.name], floc, errors)
        }
        if (errors.size != before) return null
        for (v in model.validators) {
            try {
                v(out)
            } catch (e: ValueError) {
                errors += ConfigError(loc, "Value error, ${e.message}", "value_error")
                return null
            }
        }
        return out
    }

    private fun validateField(f: F, raw: Any?, loc: List<Any>, errors: MutableList<ConfigError>): Any? {
        val value = f.before?.invoke(raw) ?: raw
        if (value == null && f.nullable) return null
        val before = errors.size
        var v = validateType(f.ty, value, loc, errors) ?: return null
        if (errors.size != before) return null
        if (!checkConstraints(f, v, loc, errors)) return null
        if (f.after != null) {
            try {
                v = f.after.invoke(v) ?: return null
            } catch (e: ValueError) {
                errors += ConfigError(loc, "Value error, ${e.message}", "value_error")
                return null
            }
        }
        return v
    }

    fun validateType(ty: Ty, value: Any?, loc: List<Any>, errors: MutableList<ConfigError>): Any? = when (ty) {
        Ty.IntT -> toInt(value, loc, errors)
        Ty.FloatT -> toFloat(value, loc, errors)
        Ty.BoolT -> toBool(value, loc, errors)
        Ty.StrT -> if (value is String) value else null.also { errors += ConfigError(loc, "Input should be a valid string", "string_type") }
        is Ty.Lit -> if (value is String && value in ty.options) value else null.also {
            errors += ConfigError(loc, "Input should be ${pyOptions(ty.options)}", "literal_error")
        }
        is Ty.ModelT -> validateModel(ty.model, value, loc, errors)
        is Ty.ListT -> if (value !is List<*>) {
            errors += ConfigError(loc, "Input should be a valid list", "list_type")
            null
        } else {
            val before = errors.size
            val items = value.mapIndexed { i, item -> validateType(ty.item, item, loc + i, errors) }
            if (errors.size != before) null else items
        }
        Ty.RuleNodeT -> validateRuleNode(value, loc, errors)
    }

    /** smart union: 그룹·조건 둘 다 시도해 하나라도 통과하면 그것, 둘 다 실패면 두 갈래 오류를 모두 기록(태그 포함). */
    private fun validateRuleNode(value: Any?, loc: List<Any>, errors: MutableList<ConfigError>): Any? {
        val groupErrors = mutableListOf<ConfigError>()
        val group = validateModel(StrategySchemas.CONDITION_GROUP, value, loc + "ConditionGroup", groupErrors)
        if (group != null) return group
        val condErrors = mutableListOf<ConfigError>()
        val cond = validateModel(StrategySchemas.CONDITION, value, loc + "Condition", condErrors)
        if (cond != null) return cond
        errors += groupErrors
        errors += condErrors
        return null
    }

    private fun checkConstraints(f: F, v: Any, loc: List<Any>, errors: MutableList<ConfigError>): Boolean {
        if (v is Number) {
            val d = v.toDouble()
            f.ge?.let { if (d < it.toDouble()) return fail(errors, loc, "Input should be greater than or equal to ${pyNum(it)}", "greater_than_equal") }
            f.gt?.let { if (d <= it.toDouble()) return fail(errors, loc, "Input should be greater than ${pyNum(it)}", "greater_than") }
            f.le?.let { if (d > it.toDouble()) return fail(errors, loc, "Input should be less than or equal to ${pyNum(it)}", "less_than_equal") }
        }
        if (v is String) {
            f.minLen?.let { if (v.length < it) return fail(errors, loc, "String should have at least $it character${if (it == 1) "" else "s"}", "string_too_short") }
            f.maxLen?.let { if (v.length > it) return fail(errors, loc, "String should have at most $it character${if (it == 1) "" else "s"}", "string_too_long") }
        }
        if (v is List<*>) {
            f.minLen?.let { if (v.size < it) return fail(errors, loc, "List should have at least $it item${if (it == 1) "" else "s"} after validation, not ${v.size}", "too_short") }
            f.maxLen?.let { if (v.size > it) return fail(errors, loc, "List should have at most $it item${if (it == 1) "" else "s"} after validation, not ${v.size}", "too_long") }
        }
        return true
    }

    private fun fail(errors: MutableList<ConfigError>, loc: List<Any>, msg: String, type: String): Boolean {
        errors += ConfigError(loc, msg, type)
        return false
    }

    private fun toInt(v: Any?, loc: List<Any>, errors: MutableList<ConfigError>): Int? = when (v) {
        is Boolean -> if (v) 1 else 0
        is Int -> v
        is Long -> v.toInt()
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && d.isFinite()) d.toInt()
            else null.also { errors += ConfigError(loc, "Input should be a valid integer, got a number with a fractional part", "int_from_float") }
        }
        is String -> v.trim().toIntOrNull() ?: null.also {
            errors += ConfigError(loc, "Input should be a valid integer, unable to parse string as an integer", "int_parsing")
        }
        else -> null.also { errors += ConfigError(loc, "Input should be a valid integer", "int_type") }
    }

    private fun toFloat(v: Any?, loc: List<Any>, errors: MutableList<ConfigError>): Double? = when (v) {
        is Boolean -> if (v) 1.0 else 0.0
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: null.also {
            errors += ConfigError(loc, "Input should be a valid number, unable to parse string as a number", "float_parsing")
        }
        else -> null.also { errors += ConfigError(loc, "Input should be a valid number", "float_type") }
    }

    private fun toBool(v: Any?, loc: List<Any>, errors: MutableList<ConfigError>): Boolean? = when (v) {
        is Boolean -> v
        is Number -> when (v.toDouble()) {
            1.0 -> true
            0.0 -> false
            else -> null.also { errors += ConfigError(loc, "Input should be a valid boolean, unable to interpret input", "bool_parsing") }
        }
        is String -> when (v.trim().lowercase()) {
            "1", "on", "t", "true", "y", "yes" -> true
            "0", "off", "f", "false", "n", "no" -> false
            else -> null.also { errors += ConfigError(loc, "Input should be a valid boolean, unable to interpret input", "bool_parsing") }
        }
        else -> null.also { errors += ConfigError(loc, "Input should be a valid boolean", "bool_type") }
    }

    private fun pyOptions(options: List<String>): String {
        val q = options.map { "'$it'" }
        return if (q.size == 1) q[0] else q.dropLast(1).joinToString(", ") + " or " + q.last()
    }
}

/** Python repr 와 같은 숫자 표기(Int 는 그대로, 실수는 최단 표현·지수는 1e-05 형식). */
fun pyNum(n: Number): String = when (n) {
    is Int, is Long -> n.toString()
    else -> pyFloat(n.toDouble())
}

fun pyFloat(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    val s = d.toString()
    val e = s.indexOf('E')
    if (e < 0) return s
    val mantissa = s.substring(0, e).removeSuffix(".0")
    val exp = s.substring(e + 1).toInt()
    if (exp in -4..15) return BigDecimal(s).toPlainString().let { if ('.' in it) it else "$it.0" }
    return mantissa + "e" + (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
}

/** Python `format(x, '.Nf')` — 정확한 이진값 기준 half-even(Java %f 의 half-up 과 다르다). */
fun pyFixed(d: Double, digits: Int): String = BigDecimal(d).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

/** Python `repr(str)`. */
fun pyRepr(s: String): String = if ('\'' in s && '"' !in s) "\"$s\"" else "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
