# viewzoo
This Trino connector stores **virtual views**, which allow improvising, extending, and re-platforming data sources while seamlessly preserving compatibility
with all applications. Virtual views are especially useful for prototyping, when updating existing applications to use Apache Iceberg, and for creating layered
hierarchies of views that can be swapped out at runtime. This connector stores virtual views to the Trino server's local filesystem, or to a local or remote
Postgresql database, without requiring any other infrastructure.

[![Claude Code](https://img.shields.io/badge/Built%20with%20Claude%20Code-6366f1?logo=claude&logoColor=white)](https://claude.ai/code)
[![CodeFactor](https://www.codefactor.io/repository/github/robfromboulder/viewzoo/badge)](https://www.codefactor.io/repository/github/robfromboulder/viewzoo)
[![Contributing](https://img.shields.io/badge/contributions-welcome-green.svg)](https://github.com/robfromboulder/viewzoo/blob/v478/CONTRIBUTING.md)
[![License](https://img.shields.io/github/license/robfromboulder/viewzoo)](https://github.com/robfromboulder/viewzoo/blob/v478/LICENSE)

Presentation at Trino Summit 2024:<br/>
[![Link to Trino Summit 2024 Presentation](https://img.youtube.com/vi/z8eh_3vBpvg/0.jpg)](https://www.youtube.com/watch?v=z8eh_3vBpvg)

Many thanks to **Roey Ogen** and **[@MirerRon](https://github.com/MirerRon)** for your feedback and contributions!

## Dependencies

* Trino 478
* Java 24
* Maven 3.9.8 or higher
* Postgresql 10 or higher (optional)

## Installation

Download Trino 478, and export `TRINO_HOME` as a shell variable:
```bash
export TRINO_HOME=$HOME/Downloads/trino-478
```

Build and install connector:
```bash
mvn clean package && rm -rf $TRINO_HOME/plugin/viewzoo && cp -r ./target/viewzoo-478 $TRINO_HOME/plugin/viewzoo
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
> Trino will fail to start if `viewzoo.dir` does not exist, or if Trino doesn't have read and write access to this directory.

## Running With JDBC Storage

Virtual views can alternatively be stored as rows in a local or remote Postgres database.
This option is preferred for production environments, since it's easy to include virtual views in regular database backups. 

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

## Using Virtual Views

Connect your favorite SQL client (like [DBeaver](https://dbeaver.io/) or [Trino CLI](https://trino.io/docs/current/client/cli.html)) to your Trino server.

Create a virtual view with static data:
```sql
create view viewzoo.example.hello as select * from (values (1, 'a')) as t (key, value)
```

Replace a virtual view with different static data:
```sql
create or replace view viewzoo.example.hello as select * from (values (1, 'a'), (2, 'b')) as t (key, value)
```

Select rows from the view:
```sql
select * from viewzoo.example.hello
```

Examine current view definition: 
```sql
show create view viewzoo.example.hello
```

Delete the view:
```sql
drop view viewzoo.example.hello
```

## Using Virtual View Hierarchies

Let's create a base view first, using static data:
```sql
create view viewzoo.example.base as select * from (values (1, 'a'), (2, 'b'), (3, 'c')) as t (key, value)
```

Next create a dependent view to do filtering (letting all rows through at first):
```sql
create view viewzoo.example.filtered as select * from viewzoo.example.base where (true)
```

Next create a dependent view to add computed columns:
```sql
create view viewzoo.example.enhanced as select *, random(100) as rand from viewzoo.example.filtered
```

Finish with a stable application-level view:
```sql
create view viewzoo.example.main as select * from viewzoo.example.enhanced
```

Finally the application has a stable top-level view that provides data:
```sql
select * from viewzoo.example.main
```

```mermaid
flowchart TD
    viewzoo.example.main --> viewzoo.example.enhanced
    viewzoo.example.enhanced --> viewzoo.example.filtered
    viewzoo.example.filtered --> viewzoo.example.base
```

Now that the view hierarchy is defined, we can replace layers at any time, without affecting the other layers! 🤩

Let's swap out the filtering layer for a different version:
```sql
create or replace view viewzoo.example.filtered as select * from viewzoo.example.base where (key not in (2))
```

> [!IMPORTANT]
> When `viewzoo.example.filtered` is changed, this new definition immediately takes affect in the view hierarchy, without having to change the definition of any other related views.

Now force random computed columns to zero for testing:
```sql
create or replace view viewzoo.example.enhanced as select *, 0 as rand from viewzoo.example.filtered
```

Now swap out the base view with real data from Postgresql:
```sql
create or replace view viewzoo.example.base as select * from postgres.example.base_v2.1
```

This ability to easily replace views within a hierarchy is especially helpful for:
* Managing JOINs/UNIONs and replication state between traditional and Iceberg storage
* Implementing right-to-be-forgotten masking layers on top of existing schemas
* Swapping between static/test datasets and real databases
* Simulating failures and testing systems with invalid data states
* Hiding schema versioning so that the application sees the right version
* Configuring computed column definitions at runtime (based on user settings)

## Limitations

> [!CAUTION]
> Viewzoo does not support materialized views. Use Iceberg for view storage in this case.

> [!CAUTION]
> Trino detects and prevents recursive view definitions, since these would cause infinite loops.

> [!CAUTION]
> There is no way to "lock" a view in order to change its definition while blocking reads. Queries will use the version of the view active when the query plan is created. Changing a view definition doesn't terminate or restart any queries running when the definition is changed.

> [!CAUTION]
> Because there is no way to lock views, hierarchies should be designed so that each layer in the hierarchy is maintained by a single actor, or only modified through synchronized access. 

> [!CAUTION]
> The easiest way to break a view hierarchy is to accidentally change column types when replacing a layer. It may be helpful to explicitly use Iceberg-specific types in base layers, even if Iceberg is only optionally configured as a storage layer, just to avoid type conversion and coercion problems later.  

---
<small>&copy; 2024-2025 Rob Dickinson (robfromboulder)</small>
