FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY config ./config
COPY src ./src
RUN mvn -q -Dmaven.repo.local=/build/.m2 -DskipTests package
FROM eclipse-temurin:17.0.19_10-jre
WORKDIR /app
COPY --from=build /build/target/ev-fleet-1.0.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8106
ENTRYPOINT ["java","-Duser.timezone=UTC","-Xms64m","-Xmx1g","-jar","/app/app.jar"]
