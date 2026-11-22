FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 coupon
USER coupon
COPY build/libs/coupon-service.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
