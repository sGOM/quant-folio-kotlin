package com.quantfolio.web.api

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * 응답 JSON 계약: snake_case, null 은 항상 포함, NaN/±Inf → null(원본 `_num.safe_float` 의미).
 * 시각은 ISO-8601 문자열(Jackson 3 기본).
 */
@Configuration(proxyBeanMethods = false)
class JsonConfig {
    @Bean
    fun quantfolioJson() = JsonMapperBuilderCustomizer { builder ->
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        builder.addModule(
            SimpleModule("non-finite-as-null")
                .addSerializer(java.lang.Double::class.java, NonFiniteAsNull())
                .addSerializer(java.lang.Double.TYPE, NonFiniteAsNull())
                .addSerializer(java.lang.Float::class.java, NonFiniteAsNull())
                .addSerializer(java.lang.Float.TYPE, NonFiniteAsNull()),
        )
    }

    private class NonFiniteAsNull<T : Number> : ValueSerializer<T>() {
        override fun serialize(value: T, gen: JsonGenerator, ctxt: SerializationContext) {
            val d = value.toDouble()
            when {
                !d.isFinite() -> gen.writeNull()
                value is Float -> gen.writeNumber(value)
                else -> gen.writeNumber(d)
            }
        }
    }
}
