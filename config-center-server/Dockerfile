FROM maven:3.9.16-eclipse-temurin-17-alpine AS builder

ARG MAVEN_REPOSITORY=https://repo.maven.apache.org/maven2
ENV MVNW_REPOURL=${MAVEN_REPOSITORY}

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config-center-server/pom.xml config-center-server/pom.xml
COPY config-center-client/pom.xml config-center-client/pom.xml

RUN chmod +x mvnw \
    && ./mvnw -q -B -s .mvn/docker-settings.xml \
        -pl config-center-server -am dependency:go-offline

COPY config-center-server/src/ config-center-server/src/

RUN ./mvnw -q -B -s .mvn/docker-settings.xml \
    -pl config-center-server -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime

RUN addgroup -S configcenter \
    && adduser -S -G configcenter configcenter

WORKDIR /app

COPY --from=builder --chown=configcenter:configcenter \
    /workspace/config-center-server/target/config-center-server-1.0.0.jar \
    /app/app.jar

USER configcenter

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
