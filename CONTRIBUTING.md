# Contributing to ViewZoo

This project uses [Claude Code](https://claude.ai/code) for development and test automation. See `CLAUDE.md` for project-specific instructions.

## Applying Security Updates

Scan for newer library versions:
```bash
mvn versions:display-dependency-updates
```

Scan for known vulnerabilities:
```bash
trivy filesystem .
```

## Running Integration Tests

These tests start Postgresql and Trino in local containers, exercise views using both filesystem and JDBC storage, and then shut down all containers (removing all volumes created during the test).

> [!IMPORTANT]
> Integration tests require `docker-compose` and network access to download Postgresql and Trino containers.

> [!IMPORTANT]
> Integration tests should exercise all features related to views, and there should be no differences in functional behavior between filesystem and JDBC configurations.

> [!TIP]
> Integration tests use port 7721 (for Trino) to avoid conflict with local services.

Run integration test profile:
```bash
mvn test -Pintegration
```
