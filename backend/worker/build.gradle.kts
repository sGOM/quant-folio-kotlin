plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":core"))
    // DB 백업 오프사이트 복제(opt-in, S3 호환 — R2/B2/MinIO). 원본 boto3 대응.
    implementation("software.amazon.awssdk:s3:2.30.0")

    testImplementation(testFixtures(project(":core")))
}
