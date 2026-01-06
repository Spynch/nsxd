FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :app:uberJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /opt/app
COPY --from=build /workspace/app/build/libs/app.jar /opt/app/app.jar

EXPOSE 5001
ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]
