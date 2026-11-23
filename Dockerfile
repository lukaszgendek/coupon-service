FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependencies first: this layer is reused until the build files change, so a code edit does not download them again.
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 coupon
USER coupon
COPY --from=build /src/build/libs/coupon-service.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=10 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
