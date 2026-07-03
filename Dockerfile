FROM gradle:8.5-jdk17-focal AS builder
WORKDIR /usr/src/app
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew shadowJar

FROM alpine:3.23.4
WORKDIR /usr/src/app
COPY --from=builder /usr/src/app/build/libs/kc-nats-listener-*.jar /usr/src/app/build/libs/
CMD ["sh", "-c", "echo Plugin JAR is located in /usr/src/app/build/libs/"]
