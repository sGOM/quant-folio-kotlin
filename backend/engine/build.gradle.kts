plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":core"))

    testImplementation(testFixtures(project(":core")))
}

// KIS 모의투자 실응답 확인 도구(조회 명령은 읽기 전용, buy·sell·amend·cancel 은 모의투자 계좌에 실제 주문) — test 소스셋의 main 이라 운영 jar 에 들어가지 않고, 어떤 태스크도 의존하지 않아 build·test 에서 실행되지 않는다.
// 사용: ./gradlew -q --console=plain :engine:kisProbe --args="orders"
tasks.register<JavaExec>("kisProbe") {
    description = "KIS 모의투자 실응답 확인 도구(조회는 읽기 전용, buy·sell·amend·cancel 은 모의투자 주문). 인자 없이 실행하면 사용법."
    classpath = project.the<SourceSetContainer>()["test"].runtimeClasspath
    mainClass = "com.quantfolio.engine.KisProbeKt"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8") // 파이프로 넘기는 출력(도구·엔진 로그)을 UTF-8 로
    isIgnoreExitValue = true // 사용법·조회 오류는 도구가 직접 출력한다 — Gradle 실패 배너로 덮지 않는다
}
