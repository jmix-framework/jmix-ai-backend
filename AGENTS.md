# AGENTS.md

Guide for coding agents working in `jmix-ai-backend`.

## Project Snapshot
- Stack: Java 17, Jmix 2.8 (Spring Boot 3, Vaadin Flow UI), Spring AI.
- Build tool: Gradle (`./gradlew`).
- Main app: `src/main/java/io/jmix/ai/backend`.
- Default app port: `8081`.

## Key Functional Areas
- Chat orchestration: `src/main/java/io/jmix/ai/backend/chat`
- Retrieval/tools/reranking integration: `src/main/java/io/jmix/ai/backend/retrieval`
- Vector store ingestion/chunking: `src/main/java/io/jmix/ai/backend/vectorstore`
- Answer checks workflow: `src/main/java/io/jmix/ai/backend/checks`
- REST endpoints:
  - `POST /chat` in `src/main/java/io/jmix/ai/backend/controller/ChatController.java`
  - `POST /api/search` in `src/main/java/io/jmix/ai/backend/controller/SearchController.java`
- Jmix entities: `src/main/java/io/jmix/ai/backend/entity`
- UI views/controllers: `src/main/java/io/jmix/ai/backend/view` and `src/main/resources/io/jmix/ai/backend/view`
- DB migrations (Liquibase): `src/main/resources/io/jmix/ai/backend/liquibase`

## Local Run
1. Start infrastructure:
```bash
docker-compose up -d
```
2. Run app:
```bash
./gradlew bootRun
```

Notes:
- `dev` profile is active by default (`src/main/resources/application.properties`).
- Dev datasource/pgvector ports are configured in `src/main/resources/application-dev.properties` (`15432`, `15433`).

## Testing
- Full test suite:
```bash
./gradlew test
```
- Tests are in `src/test/java/io/jmix/ai/backend`.
- `test` profile uses in-memory HSQLDB (`src/test/resources/application-test.properties`).

## Change Guidelines
- Keep changes scoped to the target feature; avoid unrelated refactors.
- For persistence model updates:
  - update entity classes in `src/main/java/io/jmix/ai/backend/entity`
  - add Liquibase changelog entries under `src/main/resources/io/jmix/ai/backend/liquibase/changelog`
- For new retrieval behavior, keep tool-specific logic inside `retrieval` and `vectorstore` packages, not controllers.
- Prefer constructor injection and existing patterns used in services/components.
- Preserve API contracts for `/chat` and `/api/search` unless explicitly asked to change them.

<!-- BEGIN jmix-agent-toolkit -->
## Jmix

This is a Jmix 2 application. Before writing or changing ANY file in it, read
the `jmix` skill and follow it. It maps the task to artifacts, routes each
artifact to the skill that governs it, and names the checks that close a task.
Your Jmix/Vaadin priors are not reliable here; the skills are.

Managed by the Jmix Agent Toolkit. Content between these markers is replaced on
re-install — put your own instructions outside them.
<!-- END jmix-agent-toolkit -->
