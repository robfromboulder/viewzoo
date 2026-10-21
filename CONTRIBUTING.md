# Contributing to ViewZoo

ViewZoo is developed with [Claude Code](https://claude.ai/code). Contributions are expected to be written, tested and reviewed in a Claude Code session, which reads `CLAUDE.md` for the build, test and style rules.

## Prerequisites

- Claude Code
- Java and Maven and Trino, as listed in the README
- Docker with the Compose v2 plugin (`docker compose`), for integration tests
- [Trivy](https://trivy.dev), for security scans

## Workflow

1. Check out the branch for the Trino version you're targeting (e.g. `v483`). `main` isn't used for development, and PRs target the version branch.
2. Create a feature branch from it and open a Claude Code session in the repo.
3. Describe your change, and ask Claude to implement it with tests.
4. Ask Claude to run the unit tests and the integration tests.
5. If the change touches filesystem storage, ask Claude to walk you through the manual tests, since the integration tests run Trino only on Linux.
6. Review the diff yourself, then ask Claude to commit and open a PR against the version branch.

## Security Updates

Ask Claude to check for newer dependency versions and to scan the repo with Trivy, then follow the same workflow for any upgrades.
