package com.quantfolio.web.config

import com.quantfolio.common.QuantfolioProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 프론트 오리진 하나만, 쿠키 동봉 허용. */
@Configuration(proxyBeanMethods = false)
class CorsConfig(private val props: QuantfolioProperties) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(props.frontendOrigin)
            .allowCredentials(true)
            .allowedMethods("*")
            .allowedHeaders("*")
    }
}
