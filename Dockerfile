FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

RUN apt-get update && apt-get install -y --no-install-recommends dos2unix bash \
  && rm -rf /var/lib/apt/lists/*

COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle gradle
COPY settings.gradle.kts ./
COPY gradle.properties ./
COPY app app

RUN dos2unix gradlew && chmod +x gradlew
RUN bash ./gradlew :app:uberJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/app.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
