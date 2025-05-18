# viewzoo
This Trino connector stores **virtual views**, which allow improvising, extending, and re-platforming data sources while seamlessly preserving
compatability with all applications. Virtual views are particularly useful for prototyping and updating existing applications to use Apache Iceberg.
This is a lightweight connector and does not require configuring a metastore or object storage.

This connector was originally published for my presentation on [virtual view hierarchies](https://youtu.be/z8eh_3vBpvg)
at Trino Summit 2024, and is being maintained as a small way to give back to the Trino community.

[![CodeFactor](https://www.codefactor.io/repository/github/robfromboulder/viewzoo/badge)](https://www.codefactor.io/repository/github/robfromboulder/viewzoo)
[![Contributing](https://img.shields.io/badge/contributions-welcome-green.svg)](https://github.com/robfromboulder/viewzoo/blob/main/CONTRIBUTING.md)
[![License](https://img.shields.io/github/license/robfromboulder/viewzoo)](https://github.com/robfromboulder/viewzoo/blob/main/LICENSE)

## Dependencies

* Java 23
* Trino 470

## ViewZoo Configurations
```
viewzoo.storage_type - Where the views will be stored, Either "filsystem" (default) or "jdbc".
# If viewzoo.storage_type=filesystem
viewzoo.dir
# If viewzoo.storage_type=jdbc
viewzoo.jdbc_url
viewzoo.jdbc_user
viewzoo.jdbc_password
```


## Configuring Local Environment

```
1. Install Trino
download and expand tarball to local directory
export TRINO_HOME=$HOME/...

2. Create $TRINO_HOME/etc/catalog/viewzoo.properties:
connector.name=viewzoo
viewzoo.dir=/tmp/viewzoo

3. Build the connector and redeploy
mvn clean package && rm -rf $TRINO_HOME/plugin/viewzoo /tmp/viewzoo && cp -r ./target/viewzoo-470 $TRINO_HOME/plugin/viewzoo && mkdir -p /tmp/viewzoo

4. Start Trino
cd $TRINO_HOME
bash bin/launcher run
```

## Defining and Using Views

Create a view with a static result set:
```sql
create or replace view viewzoo.example.hello as select * from (values (1, 'a'), (2, 'b'), (3, 'c')) as t (key, value)
```

Select rows from the view:
```sql
select * from viewzoo.example.hello
```

Delete the view:
```sql
drop view viewzoo.example.hello
```

---
<small>&copy; 2024-2025 Rob Dickinson (robfromboulder)</small>
