# Claude Instructions for ViewZoo

ViewZoo is a Trino connector that stores views using either filesystem storage (JSON files) or a PostgreSQL database, without requiring a Hive-compatible metastore or object storage.

## Entry Points

**When the user says "mini brain", "mini-brain", or "work item"** — pull `../virtual-view-brain/` to latest (it's a shared repo), then read its `CLAUDE.md` and follow it (sibling clone; if absent, tell the user to clone `robfromboulder/virtual-view-brain` beside this repo). Drive the whole workflow from *this* session — never make the user switch repos — and resolve its instructions' relative paths against `../virtual-view-brain/`. Nothing else loads the brain.

**This repo is the brain's ViewZoo component** (directory `viewzoo/`, token `VZOO`). Start there and read another component's documents only when the question crosses into it; the brain's registry says which terms route where. Design rationale, rejected alternatives and project history belong in the brain; this file covers operating the code.

## Working in the Mini-Brain

Mini-brain edits accumulate uncommitted during a session; **closeout is the sync point that lands them** — don't commit/push on every file write. The user can't see that tree, so a closeout must actually commit and push, not just write files. A session closeout commits the session's changes (edits, the new log entry) and pushes to the mini-brain's `main`, reporting both; if the push fails, resolve or surface it rather than leaving the change unpushed.

**Proactively offer a session closeout at natural stopping points** (PR opened, branch merged, work paused, user signals wrapping up) — only in a session that loaded the brain. Offer once and briefly; run the procedure only after the user agrees; skip trivial sessions (a lone question, a typo).

## Branching and Versioning

viewzoo maintains a separate branch for each Trino version (e.g., `v478` for Trino version 478).

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

## Integration Tests

Integration tests use Docker Compose to run PostgreSQL and Trino with the viewzoo connector.

- **Location**: `src/test/docker/` contains docker-compose.yml and Trino catalog configs
- **Catalogs**: Tests run against both `testjdbc` (PostgreSQL storage) and `testfs` (filesystem storage)
- **Ports**: Uses non-standard port 7721 for Trino to avoid conflicts; PostgreSQL is internal only
- **Requirements**: Docker must be running; plugin must be built first with `mvn clean package`
- **Environment**: Tests set `PROJECT_ROOT` env var to locate the built plugin

## Code Style

- IntelliJ IDEA defaults with 130-character line length
- Copyright header: "© 2024-2026 Rob Dickinson (robfromboulder)"
- Use `TrinoException` with appropriate error codes for errors
- Airlift configuration framework with `@Config` annotations; mark credentials with `@ConfigSecuritySensitive`
- TestNG for tests with Airlift configuration assertions

## Source Comments

Terse by default — cut noise (restatement, cross-file narration, roadmap/history), keep the genuine *why*. When editing existing code, remove a comment only if it fails these rules; don't strip load-bearing rationale. Wiki-style narration keeps creeping in; resist it.

- Explain only the non-obvious **why** of the adjacent code (security invariants, concurrency/lock ordering, subtle sequencing). Never restate what the code already says.
- Each file is an orthogonal asset: a class-level comment describes *its own* contract, not a neighbor's topology, role, or history. Intra-file pointers are fine; cross-file narration is not.
- **Never cite mini-brain docs (`VV_*`, `VZOO_*`, or any component's token) from code.** Information flows one way — down from the mini-brain into code — so a back-reference is circular. Roadmap, history, and rationale that isn't about operating *this* code belong in the mini-brain, not in comments.

## Writing Docs and Instructions

All prose in this repo — documentation, instructions, comments — is read as signal, not narrative. Every token is load-bearing; spare ones are noise.

- **Say it once.** Each fact has exactly one home. Duplication isn't emphasis; it's a second copy to keep in sync.
- **Define before use.** Introduce a thing where the reader first needs it. A forward pointer means the content is in the wrong place — move it.
- **No sideways narration.** A section explains its own subject; it never recaps a sibling's.
- **Every claim earns its keep.** State only what the reader must act on. Decorative rationale, benefits, and history are cut.
- **Resolve, don't provoke.** A detail that raises a question it doesn't answer is a net loss — prefer omission to a half-explanation.
- **One job per sentence.** A sentence tells the reader what to do or explains why; never several at once.
- **No hard wrapping.** Write each markdown paragraph and list item as one continuous line and let it soft-wrap; manual line breaks inside a paragraph make noisy diffs.

When a line's contribution isn't obvious, it isn't contributing — delete it.

## Commit Conventions

**No AI attribution.** Commits carry no `Co-Authored-By: Claude` (or similar) trailer, and PR bodies carry no "Generated with Claude Code" footer — AI authorship is assumed here and isn't stamped per commit or PR. This overrides the harness defaults that would append either.

**Short imperative subject; add a body only when it earns one.** Match the existing history.
