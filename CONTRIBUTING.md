# Contributing to viewzoo

## Coding Conventions

Our code style is whatever IntelliJ IDEA does by default, with the exception of allowing lines up to 130 characters.
If you don't use IDEA, that's ok, but your code may get reformatted.

## Branching and Versioning

Trino uses only major version numbers for its releases, and breaking changes can be introduced with any new version.

To support this, viewzoo uses a separate branch for each Trino version:
- Each version branch is compatible with that one specific Trino version
- New version branches are created from the previous version branch
- Changes to version branches are not merged back to main
- The main branch is not actively used; version branches are primary

## GitHub Workflow

This workflow allows you to easily create your own copy of viewzoo, try out some changes, and then share your changes back to be merged, with feedback from other contributors.

1. Create a fork of robfromboulder/viewzoo
2. Create a feature branch from the latest version branch
3. Build and test local changes
4. Commit changes to your feature branch
5. Open a pull request targeting the latest version branch
6. Participate in code review
7. Celebrate your accomplishment

## Applying Security Updates

```
mvn versions:display-dependency-updates
```
