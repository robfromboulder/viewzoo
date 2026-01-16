# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ViewZoo is a Trino connector that stores views using either filesystem storage (JSON files) or a PostgreSQL database, without requiring a Hive-compatible metastore or object storage.

## Branching and Versioning

Trino uses only major version numbers (478, 479, etc.) rather than semantic versioning, and breaking changes can occur with any release. viewzoo maintains a separate branch for each Trino version (e.g., `v478` for Trino version 478).

- Each version branch is compatible with that one specific Trino version
- New version branches are created from the previous version branch
- Changes to version branches are not merged back to main
- The main branch is not actively used; version branches are primary
- PRs should target the version branch, not main

## Build Commands

```bash
# Full build
mvn clean package

# Run all tests
mvn test

# Run single test
mvn test -Dtest=TestViewZooBaseConfig

# Install to Trino
mvn clean package && rm -rf $TRINO_HOME/plugin/viewzoo && cp -r ./target/viewzoo-479 $TRINO_HOME/plugin/viewzoo

# Check dependency updates
mvn versions:display-dependency-updates

# Run integration tests
mvn test -Pintegration
```

## Architecture

### Core Components

- **ViewZooPlugin** - Trino plugin entry point, registers connector factory
- **ViewZooConnectorFactory** - Creates connectors, sets up Guice DI with ViewZooModule
- **ViewZooModule** - Guice module that binds configurations and wires StorageClient via provider
- **ViewZooConnector** - Manages lifecycle and provides ViewZooMetadata
- **ViewZooMetadata** - All view CRUD operations (create/drop/list/get views), maintains in-memory cache

### Storage Layer (Provider Pattern)

- **ViewZooStorageClient** - Interface for storage backends
- **ViewZooStorageClientProvider** - Factory selecting implementation based on config
- **ViewZooLocalFileSystemClient** - Stores views as JSON files (`schema.table.json`)
- **ViewZooJdbcClient** - Stores views in PostgreSQL `viewzoo` table

### Configuration Classes

- **ViewZooBaseConfig** - `viewzoo.storage_type` (filesystem/jdbc)
- **ViewZooFilesystemConfig** - `viewzoo.dir` for JSON file storage
- **ViewZooJdbcConfig** - JDBC connection settings for PostgreSQL

## Key Patterns

- **Provider Pattern**: Storage implementation selected at runtime via ViewZooStorageClientProvider
- **Synchronized Access**: ViewZooMetadata uses synchronized methods for thread safety
- **In-Memory Caching**: Views loaded once at startup, not watched for external changes
- **Singleton Scope**: Connector, Metadata, and StorageClient are Guice singletons

## Integration Tests

Integration tests use Docker Compose to run PostgreSQL and Trino with the viewzoo connector.

- **Location**: `src/test/docker/` contains docker-compose.yml and Trino catalog configs
- **Catalogs**: Tests run against both `testjdbc` (PostgreSQL storage) and `testfs` (filesystem storage)
- **Ports**: Uses non-standard port 7721 for Trino to avoid conflicts; PostgreSQL is internal only
- **Requirements**: Docker must be running; plugin must be built first with `mvn clean package`
- **Environment**: Tests set `PROJECT_ROOT` env var to locate the built plugin

## Technical Details

- Views stored as serialized `ConnectorViewDefinition` objects
- Only supports READ_COMMITTED isolation level
- Uses Airlift configuration framework with `@Config` annotations
- Mark credentials with `@ConfigSecuritySensitive`
- Uses TestNG for tests with Airlift configuration assertions

## Code Style

- IntelliJ IDEA defaults with 130-character line length
- Copyright header: "© 2024-2026 Rob Dickinson (robfromboulder)"
- Use `TrinoException` with appropriate error codes for errors