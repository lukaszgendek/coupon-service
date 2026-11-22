FROM eclipse-temurin:21-jdk AS build
# Optional JVM flags for the build, e.g. a corporate proxy (see docker-compose.pzu.yml)
ARG JAVA_TOOL_OPTIONS=""
ENV JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS}
ARG GRADLE_ARGS=""
WORKDIR /src
COPY . .
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew bootJar --no-daemon -x test ${GRADLE_ARGS}

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 coupon
USER coupon
COPY --from=build /src/build/libs/coupon-service.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
