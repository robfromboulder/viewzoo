# Security

## Reporting a Vulnerability

Report vulnerabilities privately through [GitHub private vulnerability reporting](https://github.com/robfromboulder/viewzoo/security/advisories/new), not in a public issue.

## Supported Versions

Each version branch supports one Trino version. Fixes land only on the branch for the newest Trino version, and ship in its next release.

## Security Modes

ViewZoo stores each view's owner and security mode, `DEFINER` or `INVOKER`, and returns both to Trino unchanged. ViewZoo doesn't restrict which security mode a view uses. Trino's access control decides who can create, replace, rename and drop views.

## Who Can Change Views

ViewZoo loads every view from storage when Trino starts, and doesn't read storage again until Trino restarts. Anyone who can write to storage can add, change or remove views at the next restart, including the owner a `DEFINER` view runs as. Restrict write access to storage as tightly as administrative access to Trino.

On startup, ViewZoo skips any file or row that isn't a valid view definition, and logs a warning in `server.log`.

### Filesystem Storage

Trino needs read and write access to `viewzoo.dir`. Grant write access to that directory only to the user that runs Trino. If `viewzoo.dir` is a git checkout, anyone who can merge to its branch can change views.

ViewZoo rejects any view name whose file would resolve outside `viewzoo.dir`.

### JDBC Storage

The JDBC user needs to create the `viewzoo` table, which ViewZoo does on startup if it doesn't exist, and to select, insert, update and delete its rows. Grant write access to that table only to the JDBC user.

Keep the password out of the catalog file with Trino's environment variable substitution:

```bash
viewzoo.jdbc_password=${ENV:VIEWZOO_JDBC_PASSWORD}
```

## Resource Limits

Schema and table names are limited to 100 characters, but ViewZoo doesn't limit the length of a view definition. Every view definition stays in memory while Trino runs. A view whose SQL is longer than 20,000,000 characters is stored, but exceeds the JSON parser's string limit, so the next startup skips it with a warning.

---
<small>&copy; 2024-2026 Rob Dickinson (robfromboulder)</small>
