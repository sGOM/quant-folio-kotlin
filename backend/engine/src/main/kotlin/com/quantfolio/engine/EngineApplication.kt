package com.quantfolio.engine

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.quantfolio"])
class EngineApplication

fun main(args: Array<String>) {
    runApplication<EngineApplication>(*args)
}
