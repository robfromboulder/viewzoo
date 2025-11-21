# Contributing to viewzoo

## Using Claude Code

This project uses (but does not require) [Claude Code](https://claude.ai/code) for help with development, testing, and maintenance. The `CLAUDE.md` file provides project context for Claude Code sessions.

If you haven't used Claude Code yet, these resources are super helpful:
* https://code.claude.com/docs
* https://www.anthropic.com/engineering/claude-code-best-practices
* https://www.siddharthbharath.com/claude-code-the-complete-guide/

## Branching and Versioning

Trino uses only major version numbers for its releases, and breaking changes can be introduced with any new version. To support this, viewzoo uses a separate branch for each Trino version:
- Each version branch is compatible with that one specific Trino version
- New version branches are created from the previous version branch
- Changes to version branches are not merged back to main
- The main branch is not actively used; version branches are primary

## GitHub Workflow

This workflow allows you to easily create your own copy of viewzoo, try out some changes, and then share your changes back to be merged, with feedback from other contributors.

1. Create a fork of robfromboulder/viewzoo
2. Create a feature branch from the latest version branch
3. Build and test your local changes
4. Add or update [integration tests](#running-integration-tests)
5. Commit changes to your feature branch
6. Open a pull request targeting the latest version branch
7. Participate in code review
8. Celebrate your accomplishment

## Coding Conventions

Our code style is whatever IntelliJ IDEA does by default, with the exception of allowing lines up to 130 characters. If you don't use IDEA, that's ok, but your code may get reformatted later.

All source files should use this copyright statement:
```
© 2024-2025 Rob Dickinson (robfromboulder)
```

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

```bash
mvn test -Pintegration
```

> [!CAUTION]
> Integration tests require `docker-compose` and network access to download Postgresql and Trino containers.

> [!TIP]
> Integration tests use port 7720 (Postgresql) and port 7721 (Trino) to avoid conflict with other running services.
