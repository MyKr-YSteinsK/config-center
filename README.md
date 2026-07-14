# config-center

A lightweight configuration center and Feature Flag learning project built with Java 17, Spring Boot 3, and Maven multi-module.

The repository is currently in a stabilization and refactoring phase. The immediate goal is to make the existing behavior correct, testable, and consistently documented before adding new capabilities.

## Current capabilities

- Configuration management by `app + env + key`
- Configuration versioning, history, and rollback
- Feature Flag management
- Allowlist and stable percentage rollout
- ETag / `If-None-Match` conditional requests
- Long-polling configuration watch
- Java client with disk cache, retry, fallback, and a basic circuit breaker
- Basic API Key authorization for configuration writes
- Actuator, Micrometer, Prometheus endpoint, and CI verification

Some existing mechanisms are being revised. See:

- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/patch-log.md`

## Technology stack

- Java 17
- Spring Boot 3
- Maven
- Spring Web
- Spring Data JPA
- H2
- Spring Boot Actuator
- Micrometer
- springdoc-openapi
- JUnit 5
- GitHub Actions
- JaCoCo

## Modules

```text
config-center/
├── config-center-server/
├── config-center-client/
├── docs/
├── AGENTS.md
├── examples.http
├── pom.xml
└── README.md
```

## Build

Prerequisite: JDK 17. The Maven Wrapper downloads Maven 3.9.16 on first use.

```bash
./mvnw -q -B clean verify
```

## Run server

```bash
./mvnw -pl config-center-server spring-boot:run
```

## Run client

Start the server first, then run:

```bash
./mvnw -pl config-center-client spring-boot:run
```

## Local endpoints

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 Console: `http://localhost:8080/h2-console`
- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus`
