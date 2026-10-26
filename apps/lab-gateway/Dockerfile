FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps/ apps/
COPY contracts/ contracts/
RUN mvn -B -ntp -pl apps/lab-gateway -am package

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/apps/lab-gateway/target/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8081 8082
ENV SERVER_PORT=8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=5 \
  CMD curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${SERVER_PORT}/health" > /dev/null
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
