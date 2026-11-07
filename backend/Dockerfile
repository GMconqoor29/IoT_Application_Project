FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY contracts ./contracts
COPY backend ./backend
RUN mvn -B -f backend/pom.xml package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/target/airsense-backend-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
