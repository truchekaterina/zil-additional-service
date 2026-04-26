FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY settings.gradle .
COPY build.gradle .
COPY src src

RUN ["java", "-classpath", "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain", "clean", "bootJar", "-x", "test", "--no-daemon"]

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
