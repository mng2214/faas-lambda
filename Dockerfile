FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY build/libs/*.jar /app/app.jar

CMD ["java", "-jar", "/app/app.jar"]