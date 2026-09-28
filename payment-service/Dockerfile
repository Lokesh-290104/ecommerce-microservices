# Single-stage image (design D25): the jar is built outside Docker by ./mvnw package,
# because the JDK lives on Windows/CI and Docker lives in WSL.
FROM eclipse-temurin:21-jre

RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY target/payment-service.jar app.jar
USER app

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
