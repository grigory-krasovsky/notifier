# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Greenfield Spring Boot **4.0.6** (Java **17**) application named `Notifier`, intended to become a **Telegram notifier bot** backed by PostgreSQL. Right now it is only the Spring Initializr scaffold: a single `@SpringBootApplication` entry point (`com.example.notifier.NotifierApplication`) and the default `contextLoads` test. There is no bot, web, or persistence code yet — expect to create the package structure under `src/main/java/com/example/notifier/`.

## Build / run / test

There is **no system `mvn`** — always use the Maven wrapper. The wrapper is `only-script` type and pins Maven 3.9.15.

```bash
./mvnw spring-boot:run                       # run the app
./mvnw -B -ntp -DskipTests package           # build the boot jar (target/Notifier-0.0.1-SNAPSHOT.jar)
./mvnw test                                   # run all tests
./mvnw test -Dtest='NotifierApplicationTests#contextLoads'   # run a single test / method
```

On Windows PowerShell/cmd use `.\mvnw.cmd ...`; the `./mvnw` form above is for the Bash/git-bash shell.

**JDK note:** the project compiles at release 17 (IntelliJ SDK is `corretto-17`). The machine's default `java` on PATH is 21 — Maven still targets 17 via the compiler plugin, so don't "upgrade" `java.version` to match PATH.

## Critical gotcha: the app/tests need a datasource to start

`./mvnw test` currently **fails** at `contextLoads` with *"Failed to determine a suitable driver class"*. This is expected, not a regression: `spring-boot-starter-data-jpa` + the PostgreSQL driver auto-configure a `DataSource`, but `application.properties` sets no `spring.datasource.*` and there is no embedded DB (H2) on the classpath. So **a green `compile` does not mean green `test`.** Before the context can load you must do one of:

- point `spring.datasource.{url,username,password}` at a running PostgreSQL, or
- add an embedded/test database (H2 test scope) or Testcontainers for the test context, or
- for tests that don't need JPA, exclude the DataSource auto-configuration.

## Things that are non-obvious and must be preserved

- **Spring Boot 4.0 starter names differ from 3.x.** This project uses `spring-boot-starter-webmvc` (not `-web`) and the split test starters `spring-boot-starter-webmvc-test` and `spring-boot-starter-data-jpa-test`. Don't "correct" these to the 3.x names.
- **Lombok** is wired through explicit `annotationProcessorPaths` in the `maven-compiler-plugin` config (both `default-compile` and `default-testCompile` executions), and excluded from the boot jar. If you edit the build, keep those processor paths or Lombok-generated code stops compiling.
- The pom carries empty `<license>`, `<developers>`, `<scm>` overrides on purpose (to block inheritance from the Spring Boot parent) — leave them unless changing the parent.

## IntelliJ

If the Maven tool window / run gutters are missing, the folder was opened without linking the build: right-click **`pom.xml` → Add as Maven Project** (or accept the "Load Maven Project" / "Trust Project" prompt). Opening the directory alone does not import it as Maven.

## Repo conventions

Default branch is **`master`** (not `main`). Commits in this repo use the local email `grigory.krasovsky@gmail.com`.
