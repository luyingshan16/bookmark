FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src/main ./src/main
RUN sh ./gradlew --no-daemon bootJar \
    && cp build/libs/*.jar /workspace/bookmark.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN install -d -o 10001 -g 10001 /app/uploads/avatars
COPY --from=build /workspace/bookmark.jar /app/bookmark.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bookmark.jar"]
