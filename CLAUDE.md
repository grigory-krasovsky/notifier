# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Предпочитаемый язык общения с пользователем — **русский**: отвечай и веди обсуждения на русском. Код, идентификаторы и commit-сообщения остаются на английском.

## What this is

Greenfield Spring Boot **4.0.6** (Java **17**) application named `Notifier` — a **Telegram notifier bot** backed by PostgreSQL. The persistence layer exists (JPA entities in `domain/`, Spring Data repositories in `repository/`, Flyway schema in `src/main/resources/db/migration`), and the Telegram layer (`telegram/`, library `telegrambots` 9.x, long polling) handles `/start` with timezone onboarding. The scheduler is not written yet. Business rules and the data model are documented in **`docs/DESIGN.md`** — read it before touching domain logic; agreed terminology: *occurrence* (срабатывание) = initial scheduled notification, *reminder* (напоминание) = repeat while an occurrence stays open.

## Build / run / test

There is **no system `mvn`** — always use the Maven wrapper. The wrapper is `only-script` type and pins Maven 3.9.15.

```bash
./mvnw spring-boot:run                       # run the app on the host (needs `docker compose up -d db`)
./mvnw -B -ntp -DskipTests package           # build the boot jar (target/Notifier-0.0.1-SNAPSHOT.jar)
./mvnw test                                   # run all tests
./mvnw test -Dtest='NotifierApplicationTests#contextLoads'   # run a single test / method
docker compose up -d --build                 # run everything (app + db) in Docker locally; app reads .env
```

**Deploy:** push to `master` triggers `.github/workflows/deploy.yml` (test → build image to GHCR → SSH deploy to the VPS). `docker-compose.prod.yml` is the server-side compose (GHCR image, not `build:`). One-time setup and the secrets list live in **`docs/DEPLOY.md`**.

On Windows PowerShell/cmd use `.\mvnw.cmd ...`; the `./mvnw` form above is for the Bash/git-bash shell.

**JDK note:** the project compiles at release 17 (IntelliJ SDK is `corretto-17`). The machine's default `java` on PATH is 21 — Maven still targets 17 via the compiler plugin, so don't "upgrade" `java.version` to match PATH.

## Critical gotcha: tests need Docker, the app needs PostgreSQL

- `./mvnw test` runs against **Testcontainers** (real PostgreSQL 17). **Docker Desktop must be running**, otherwise tests fail with "Could not find a valid Docker environment". A green `compile` still does not mean green `test`.
- `./mvnw spring-boot:run` expects PostgreSQL on `localhost:5432` — start it with `docker compose up -d`. Datasource defaults in `application.properties` match `docker-compose.yml`; override via `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` env vars.
- The schema is owned by **Flyway** (`ddl-auto=validate`). Never let Hibernate generate DDL — add a new `V<n>__*.sql` migration under `src/main/resources/db/migration` instead.
- The bot token comes **only** from the `TELEGRAM_BOT_TOKEN` env var (property `notifier.telegram.bot-token`). Locally it lives in `.env` (git-ignored; template in `.env.example`). Never hardcode or commit it.
- **`api.telegram.org` is NOT directly reachable from this machine's JVM or Docker containers** (ISP-level block; the host runs AmneziaVPN with split tunneling that covers only some processes — `curl` gets through, Java/.NET/containers do not). General internet egress from Docker works; only Telegram is affected. The bot supports routing its traffic through a proxy via `TELEGRAM_PROXY_HOST`/`TELEGRAM_PROXY_PORT`/`TELEGRAM_PROXY_TYPE` (SOCKS|HTTP; from Docker use `host.docker.internal`). Bot registration failure does not crash the app — it logs an ERROR and keeps running.

## Things that are non-obvious and must be preserved

- **Spring Boot 4.0 starter names differ from 3.x.** This project uses `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`, and the split test starters `spring-boot-starter-webmvc-test` and `spring-boot-starter-data-jpa-test`. Don't "correct" these to the 3.x names.
- **Testcontainers 2.x naming differs from 1.x.** Artifacts are `testcontainers-postgresql` / `testcontainers-junit-jupiter` (not `postgresql` / `junit-jupiter`), and `PostgreSQLContainer` lives in `org.testcontainers.postgresql` (not `org.testcontainers.containers`). Don't "fix" these to the 1.x forms from old tutorials.
- **Lombok** is wired through explicit `annotationProcessorPaths` in the `maven-compiler-plugin` config (both `default-compile` and `default-testCompile` executions), and excluded from the boot jar. If you edit the build, keep those processor paths or Lombok-generated code stops compiling.
- The pom carries empty `<license>`, `<developers>`, `<scm>` overrides on purpose (to block inheritance from the Spring Boot parent) — leave them unless changing the parent.

## IntelliJ

If the Maven tool window / run gutters are missing, the folder was opened without linking the build: right-click **`pom.xml` → Add as Maven Project** (or accept the "Load Maven Project" / "Trust Project" prompt). Opening the directory alone does not import it as Maven.

## Repo conventions

Default branch is **`master`** (not `main`). Commits in this repo use the local email `grigory.krasovsky@gmail.com`.
