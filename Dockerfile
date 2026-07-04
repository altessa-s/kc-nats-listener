# The Gradle version is pinned by the wrapper (gradle/wrapper/gradle-wrapper.properties),
# so a plain JDK image is enough for the build stage.
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /usr/src/app
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM alpine:3.23.4
WORKDIR /usr/src/app
COPY --from=builder /usr/src/app/build/libs/kc-nats-listener-*.jar /usr/src/app/build/libs/
CMD ["sh", "-c", "echo Plugin JAR is located in /usr/src/app/build/libs/"]
