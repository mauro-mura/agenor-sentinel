# One image per Spring Boot role, built from the jar Maven already produced:
#   mvn -DskipTests package && docker compose build
FROM docker.io/library/eclipse-temurin:21.0.12_8-jre-alpine-3.24
ARG MODULE
COPY ${MODULE}/target/${MODULE}-*.jar /tmp/app.jar
# @friction F1: Agenor 0.39.0's package scanning (agenor.agents.base-package) finds no agent when
# the application runs as a Spring Boot executable jar with nested libraries, and the runtime
# starts with zero agents. Started from Spring Boot's extracted layout, it finds them. Remove the
# extraction when the release that fixes F1 is out.
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app && rm /tmp/app.jar
WORKDIR /app
# exec form, so the JVM is PID 1 and receives SIGTERM directly (regime 2: orderly stop).
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
