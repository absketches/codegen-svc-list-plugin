# AGENTS.md

This file gives AI coding agents guidance for working on **codegen-svc-list-plugin** – a Maven plugin that scans classpaths to generate:

- A `.properties` index of concrete implementations for configured base classes.
- An optional `reflect-config.json` to support GraalVM Native Image.

The main goals: **stay fast, avoid unnecessary classloading, and keep the input & output formats stable.**

---

## Dev environment & setup

**Requirements**

- Java **21**
- Maven **3.9.11+**

**Clone & build**

```bash
git clone https://github.com/absketches/codegen-svc-list-plugin.git
cd codegen-svc-list-plugin

# Run unit tests & basic checks
mvn clean test

# Full verification (recommended before PRs)
mvn clean verify

# Install locally so other projects can use the plugin
mvn clean install
```
If you introduce new modules or profiles, make sure mvn clean verify still succeeds with default settings.

## Project structure (for navigation)

Standard single-module Maven layout:
pom.xml – Maven plugin definition, dependencies, and build config.
src/main/java/... – Plugin implementation (Mojo(s) and helpers).
src/test/java/... – Unit tests for plugin behavior.
.github/ – CI workflows (GitHub Actions).
README.md – Human-facing description and usage examples.
LICENSE – Apache-2.0.
When adding new Java files, follow the existing package structure and naming patterns.

## Core plugin behavior (do not break)

### The plugin consumes:
Accepts one or more base classes (usually abstract types) via configuration.
Scans the module’s compiled classes and its dependencies.
### The plugin Produces:
A services.properties file at META-INF/io/github/absketches/plugin/services.properties - the file name is configurable.
A reflect-config.json under META-INF/native-image/<group>/<artifact>/ which is enabled by default.

## Testing Guidelines
- Write component tests in `src/test/java`, naming files `*Test` and methods with `should...` to express behavior.

## Commit & Pull Request Guidelines
- Use Conventional Commits, example: (`feat: generate reflect-config.json in build output`) for every change.
- Branch from `main`, describe intent, link issues, and attach logs/screenshots for behavioral changes or UI assets.
- PRs must list test evidence (`./mvnw clean verify` output) and restate any config toggles touched.


## Coding style & conventions
- Language: Java 21.
- Packaging: this is a Maven plugin; keep <packaging>maven-plugin</packaging> in pom.xml.
- Follow existing formatting in the repo: 4-space indentation.
- Clear, descriptive names; avoid overly clever abstractions.
- No heavy dependencies; keep the plugin lean and friendly for build pipelines.
- Logging: Use Maven’s Log (via getLog()) – never System.out.println or similar in production code.
- Error handling: Fail the build with meaningful error messages when configuration is invalid.
- Prefer explicit exceptions over silently ignoring misconfigurations.
- When touching pom.xml: Avoid adding runtime-heavy dependencies. Keep versions aligned with the project’s current choices (Maven and plugin tooling versions).
- If you introduce a new dependency, justify its need in code comments or commit/PR description.

