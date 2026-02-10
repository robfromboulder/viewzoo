# viewzoo
This Trino connector stores views to the local filesystem or a Postgresql database, without requiring Hive metastore or object storage services. 

Many thanks to **Roey Ogen** and **[@MirerRon](https://github.com/MirerRon)** for your feedback and contributions!

[![Claude Code](https://img.shields.io/badge/Built%20with%20Claude%20Code-6366f1?logo=claude&logoColor=white)](https://claude.ai/code)
[![CodeFactor](https://www.codefactor.io/repository/github/robfromboulder/viewzoo/badge)](https://www.codefactor.io/repository/github/robfromboulder/viewzoo)
[![Contributing](https://img.shields.io/badge/contributions-welcome-green.svg)](https://github.com/robfromboulder/viewzoo/blob/v479/CONTRIBUTING.md)
[![License](https://img.shields.io/github/license/robfromboulder/viewzoo)](https://github.com/robfromboulder/viewzoo/blob/v479/LICENSE)

> [!NOTE]
> This project is not officially endorsed or supported by Starburst.

## Dependencies

* Trino 479
* Java 25
* Maven 3.9.8 or higher
* Postgresql 10 or higher (optional)

## Installation

Download Trino 479, and export `TRINO_HOME` as a shell variable:
```bash
export TRINO_HOME=$HOME/Downloads/trino-479
```

Build and install connector:
```bash
mvn clean package && rm -rf $TRINO_HOME/plugin/viewzoo && cp -r ./target/viewzoo-479 $TRINO_HOME/plugin/viewzoo
```

> [!WARNING]
> Don't run Trino yet, you'll need to configure local storage or JDBC storage first.

## Running With Filesystem Storage

Views can be stored directly on the Trino server's filesystem (as JSON files), without requiring any other infrastructure.
This option is intended for development and prototyping, and for very lightweight deployments.

Create a local directory to store views:
```bash
rm -rf /tmp/viewzoo && mkdir -p /tmp/viewzoo
```

Create a `$TRINO_HOME/etc/catalog/viewzoo.properties` configuration file like this:
```bash
connector.name=viewzoo
viewzoo.storage_type=filesystem
viewzoo.dir=/tmp/viewzoo
```

Run Trino:
```bash
cd $TRINO_HOME && bash bin/launcher run
```

> [!CAUTION]
> Trino will fail to start if `viewzoo.dir` does not exist, or if Trino doesn't have read and write permissions.

> [!CAUTION]
> If running Trino in a Docker container, ensure `viewzoo.dir` is mapped to a persistent volume or host directory. Without this, view definitions will be lost when the container is upgraded or recreated. To verify your volume mount is configured correctly, run:
> ```bash
> docker inspect <container_name> --format='{{json .Mounts}}' | jq '.[] | select(.Destination == "/path/to/viewzoo.dir")'
> ```
> Replace `/path/to/viewzoo.dir` with your actual `viewzoo.dir` path. If this returns mount information showing a `Source` outside the container, your views are safely persisted. If it returns nothing or shows no external `Source`, your views will be lost on container upgrade.

> [!TIP]
> Filesystem storage enables a unique collaboration pattern: teams can share view definitions through git without requiring a traditional database or network file share. By storing views as JSON files in a version-controlled directory, view definitions become part of your application's source code, with full revision history, pull request reviews, and branch-based development. This approach is particularly valuable for development environments, CI/CD pipelines, and scenarios where you need view definitions to be as portable and reviewable as your application code itself.

## Running With JDBC Storage

Views can alternatively be stored as rows in a local or remote Postgres database. This option is preferred for multi-container and Kubernetes deployments, and production environments in general. JDBC storage makes it easy to include views in regular database backups and avoids the sharing, permission, and upgrade issues that can occur with filesystem storage in containerized production environments. 

Run a local Postgres server if necessary:
```bash
docker run -d --name viewzoopg -e POSTGRES_PASSWORD=secretpassword -p 5432:5432 postgres:16
```

Create a `$TRINO_HOME/etc/catalog/viewzoo.properties` configuration file like this:
```bash
connector.name=viewzoo
viewzoo.storage_type=jdbc
viewzoo.jdbc_url=jdbc:postgresql://localhost:5432/postgres
viewzoo.jdbc_user=postgres
viewzoo.jdbc_password=secretpassword
```

Run Trino:
```bash
cd $TRINO_HOME && bash bin/launcher run
```

> [!CAUTION]
> Trino will fail to start if Postgres is not running or reachable, or if JDBC authentication fails.

When finished testing, remove local Postgres server:
```bash
docker stop viewzoopg; docker rm viewzoopg
```

## Using Views

Views managed by this connector behave just like regular Trino views, but we'll walk through some simple examples anyway.

Connect your favorite SQL client (like [DBeaver](https://dbeaver.io/) or [Trino CLI](https://trino.io/docs/current/client/cli.html)) to your Trino server.

Create a view with static data:
```sql
create view viewzoo.example.hello as select * from (values ('A', '1')) as t (key, value)
```

Select rows from the view:
```sql
select * from viewzoo.example.hello
```

Show view columns and types:
```sql
describe viewzoo.example.hello
```

Replace view with different static data:
```sql
create or replace view viewzoo.example.hello as select * from (values ('A', '1'), ('B', '4')) as t (key, value)
```

Replace view with query to system catalog:
```sql
create or replace view viewzoo.example.hello as select node_id as key, http_uri as value from system.runtime.nodes
```

Examine current view definition: 
```sql
show create view viewzoo.example.hello
```

Delete the view:
```sql
drop view viewzoo.example.hello
```

## Limitations

> [!CAUTION]
> This connector does not support defining or using tables, only views.

> [!CAUTION]
> Materialized views are not supported. Use Iceberg for view storage in this case.

> [!CAUTION]
> While this connector probably works with multiple versions of Trino, it has only been tested with Trino 479.

---
<small>&copy; 2024-2026 Rob Dickinson (robfromboulder)</small>
