FROM eclipse-temurin:21-jdk AS build
# Optional JVM flags for the build, e.g. a corporate proxy (see docker-compose.pzu.yml)
ARG JAVA_TOOL_OPTIONS=""
ENV JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS}
ARG GRADLE_ARGS=""
WORKDIR /src
# Dependencies first: this layer is reused until the build files change, so a code edit does not download them again.
# pzu/ is a local, git-ignored folder; .gitattributes is only there so the glob always matches something.
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY .gitattributes pzu*/ ./pzu/
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon ${GRADLE_ARGS}
COPY src src
RUN ./gradlew bootJar --no-daemon -x test ${GRADLE_ARGS}

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 coupon
USER coupon
COPY --from=build /src/build/libs/coupon-service.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=10 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
