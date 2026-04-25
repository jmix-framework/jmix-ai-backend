# START HERE

This file is the fastest entry point for contributors and operators.

## 1) What this project is

`jmix-ai-backend` is a Jmix + Spring AI backend with:
- chat and search APIs,
- admin UI,
- knowledge ingestion into pgvector,
- answer quality checks,
- optional business-documents mode with deterministic numeric answers.

Primary runtime URLs (local dev):
- App UI: `http://localhost:8081`
- Chat API: `POST http://localhost:8081/chat`
- Search API: `POST http://localhost:8081/api/search`
- Reranker: `http://localhost:8000`
- OpenAI-compatible model endpoint (LM Studio or similar): `http://localhost:6666`

## 2) 5-minute local start

1. Start infra:
```bash
docker-compose up -d
```

2. Ensure your chat/embedding model server is up on `http://localhost:6666`.

3. Start backend:
```bash
./gradlew bootRun
```

4. Open UI:
- `http://localhost:8081`
- default dev credentials: `admin / admin`

## 3) Read these docs next

- Repo overview and architecture: [`README.md`](README.md)
- Environment and dependency requirements: [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
- Day-2 operations and troubleshooting: [`docs/RUNBOOK.md`](docs/RUNBOOK.md)
- Agent-specific coding constraints: [`AGENTS.md`](AGENTS.md)

## 4) Current chat profiles (seeded on startup)

Seed files:
- `io/jmix/ai/backend/init/default-params-chat.yml` (`classifier_rag`)
- `io/jmix/ai/backend/init/default-params-chat-always-rag.yml` (`always_rag`)
- `io/jmix/ai/backend/init/default-params-chat-narrow-rag.yml` (`narrow_rag`, business docs)

`narrow_rag` is intended for business-document queries and uses `business_documents_retriever`.

## 5) Before pushing

Use this minimum check:
```bash
./gradlew test
```

And ensure no local artifacts are staged (dumps, temporary screenshots, local agent folders).
