# Requirements

## Runtime and tooling

- OS: Linux/macOS/WSL (Windows possible with equivalent tooling)
- JDK: 17+ required, 21 recommended for local development
- Gradle: wrapper is used (`./gradlew`)
- Docker + Docker Compose
- Python 3.10 (only if running reranker outside Docker)

## Local ports

Default local development ports:
- `8081` - Jmix AI Backend app (UI + API)
- `15432` - main PostgreSQL database
- `15433` - pgvector database
- `8000` - reranker service
- `6666` - OpenAI-compatible chat/embedding endpoint (LM Studio or equivalent)
- `4317`, `4318` - optional OpenTelemetry collector

## Required services

Minimum required to run app locally:
- PostgreSQL (`main.datasource.*`)
- pgvector database (`pgvector.datasource.*`)
- OpenAI-compatible chat endpoint (`spring.ai.openai.base-url`)
- OpenAI-compatible embedding endpoint (`rag.embedding.base-url`)

Reranker can run from Docker Compose (`reranker` service) or separately via Python.

## Configuration sources

Main config files:
- `src/main/resources/application.properties`
- `src/main/resources/application-dev.properties`

`dev` profile is active by default.

## Environment variables

Commonly used variables:
- `OPENAI_BASE_URL` (if overriding `spring.ai.openai.base-url`)
- `OPENAI_API_KEY` (can be dummy for local non-auth endpoints)
- `ANSWER_CHECKS_EVALUATOR_API_KEY` (required if evaluator points to hosted API)
- `ANSWER_CHECKS_EVALUATOR_BASE_URL`
- `ANSWER_CHECKS_EVALUATOR_MODEL`

## Data prerequisites

If you use `narrow_rag` business mode, ensure business docs are ingested into vector store.

Business documents default local path:
- `.jmix/work/business-documents`

## Verified local defaults in this repository

- App uses `http://localhost:6666` for chat and embeddings in `application-dev.properties`.
- Embedding model in dev defaults to `ggml-org/bge-m3-Q8_0-GGUF` with dimensions `1024`.
- Admin dev credentials:
  - username: `admin`
  - password: `admin`
