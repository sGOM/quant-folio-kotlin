# web·engine·worker 세 앱을 한 이미지에 담고 compose 의 command 로 고른다(원본과 같은 구조).
# 테스트는 Testcontainers 라 docker build 안에서 못 돈다 — CI(./gradlew build)가 맡는다.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY . .
RUN ./gradlew :web:bootJar :engine:bootJar :worker:bootJar --no-daemon --console=plain \
 && mkdir /out \
 && cp web/build/libs/web-*-SNAPSHOT.jar /out/web.jar \
 && cp engine/build/libs/engine-*-SNAPSHOT.jar /out/engine.jar \
 && cp worker/build/libs/worker-*-SNAPSHOT.jar /out/worker.jar

# noble(24.04)로 고정 — 배포판의 postgresql-client 가 서버와 같은 16 이다. 낮으면 덤프를 거부하고,
# 높으면(17+) 덤프에 pg16 이 모르는 설정(transaction_timeout)이 들어가 복원 때 오류가 난다.
FROM eclipse-temurin:25-jre-noble
# curl: web 헬스체크. postgresql-client: worker 야간 백업의 pg_dump.
RUN apt-get update && apt-get install -y --no-install-recommends curl postgresql-client \
 && rm -rf /var/lib/apt/lists/* \
 && test "$(pg_dump --version | grep -oE '[0-9]+' | head -1)" -eq 16
WORKDIR /app
COPY --from=build /out/ /app/
EXPOSE 8000
# exec 형식 — java 가 PID 1 이라 SIGTERM 을 직접 받아 엔진이 러너를 정리하고 내려간다.
CMD ["java", "-jar", "/app/web.jar"]
