# viewzoo
This Trino connector stores **virtual views**, which allow improvising, extending, and re-platforming data sources while seamlessly preserving compatibility
with all applications. Virtual views are especially useful for prototyping, when updating existing applications to use Apache Iceberg, and for creating layered
hierarchies of views that can be swapped out at runtime. This connector stores virtual views to the Trino server's local filesystem, or to a local or remote
Postgresql database, without requiring any other infrastructure.

[![CodeFactor](https://www.codefactor.io/repository/github/robfromboulder/viewzoo/badge)](https://www.codefactor.io/repository/github/robfromboulder/viewzoo)
[![Contributing](https://img.shields.io/badge/contributions-welcome-green.svg)](https://github.com/robfromboulder/viewzoo/blob/v470/CONTRIBUTING.md)
[![License](https://img.shields.io/github/license/robfromboulder/viewzoo)](https://github.com/robfromboulder/viewzoo/blob/v470/LICENSE)

Presentation at Trino Summit 2024:<br/>
[![Link to Trino Summit 2024 Presentation](https://img.youtube.com/vi/z8eh_3vBpvg/0.jpg)](https://www.youtube.com/watch?v=z8eh_3vBpvg)

Many thanks to **Roey Ogen** and **[@MirerRon](https://github.com/MirerRon)** for your feedback and contributions!

## Dependencies

* Trino 470
* Java 23
* Maven 3.9.8 or higher
* Postgresql 10 or higher (optional)

## Installation

Download Trino 470, and export `TRINO_HOME` as a shell variable:
```bash
export TRINO_HOME=$HOME/Downloads/trino-470
```

Build and install connector:
```bash
mvn clean package && rm -rf $TRINO_HOME/plugin/viewzoo && cp -r ./target/viewzoo-470 $TRINO_HOME/plugin/viewzoo
```

> [!WARNING]
> Don't run Trino yet, you'll need to configure local storage or JDBC storage first.

## Running With Filesystem Storage

Virtual views can be stored directly on the Trino server's filesystem (as JSON files), without requiring any other infrastructure.
This option is intended for development and prototyping, and for very lightweight deployments.

Create a local directory to store virtual views:
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
> Trino will fail to start if `viewzoo.dir` does not exist, or if Trino doesn't have read access to this directory.

## Running With JDBC Storage

Virtual views can alternatively be stored as rows in a local or remote Postgres database.
This option is preferred for production environments, since it's easy to include virtual views in regular database backups. 

Run a local Postgres server if necessary:
```bash
docker run -d --name viewzoopg -e POSTGRES_PASSWORD=secretpassword -p 5432:5432 postgres
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

## Using Virtual Views

Connect your favorite SQL client (like [DBeaver](https://dbeaver.io/) or [Trino CLI](https://trino.io/docs/current/client/cli.html)) to your Trino server.

Create a virtual view with static data:
```sql
create or replace view viewzoo.example.hello as select * from (values (1, 'a'), (2, 'b'), (3, 'c')) as t (key, value)
```

Select rows from the view:
```sql
select * from viewzoo.example.hello where key > 1
```

Delete the view:
```sql
drop view viewzoo.example.hello
```

---
<small>&copy; 2024-2025 Rob Dickinson (robfromboulder)</small>
