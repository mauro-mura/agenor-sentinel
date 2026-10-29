# One image per role, built from the jar Maven already produced: mvn -DskipTests package
#   docker compose build
FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24
ARG MODULE
COPY ${MODULE}/target/${MODULE}-*.jar /app/app.jar
WORKDIR /app
# exec form, so the JVM is PID 1 and receives SIGTERM directly (regime 2: orderly stop).
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
