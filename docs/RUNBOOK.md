# Runbook

Operational guide for local development and smoke checks.

## Start and stop

Start infrastructure:
```bash
docker-compose up -d
```

Start backend:
```bash
./gradlew bootRun
```

Stop infrastructure:
```bash
docker-compose down
```

## Health checks

Backend health:
```bash
curl -s http://127.0.0.1:8081/actuator/health
```

Model endpoint availability:
```bash
curl -sS http://127.0.0.1:6666/v1/models
```

If model server is up but returns:
- `{"error":"No models loaded..."}`
then load a model in LM Studio first.

## API smoke tests

### Chat API

```bash
curl -sS -X POST http://127.0.0.1:8081/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"smoke-chat-1","text":"Что такое DataManager в Jmix?"}'
```

### Search API

```bash
curl -sS -X POST http://127.0.0.1:8081/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"DataManager"}'
```

## Profile behavior and expected usage

Seeded chat profiles are created on startup:
- `classifier_rag` (default)
- `always_rag`
- `narrow_rag` (business-docs oriented)

`narrow_rag` expects business-document queries and uses only `business_documents_retriever`.

## Business-documents accuracy checks

Known-good checks for business mode:

```bash
curl -sS -X POST http://127.0.0.1:8081/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"biz-1","text":"Какая общая сумма заказов у клиента ООО \"Альфа Трейд\"?"}'
```

```bash
curl -sS -X POST http://127.0.0.1:8081/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"biz-2","text":"Какая общая сумма заказов у клиента ООО \"Ромашка\"?"}'
```

Expected semantics:
- known client -> exact deterministic total from ingested orders
- unknown client -> explicit "not found in uploaded business documents"

## Troubleshooting

### 1) Chat hangs or long timeouts against local model server

Symptoms:
- request stalls, empty response, or connector errors around response headers.

Checks:
- verify `http://127.0.0.1:6666/v1/models` responds quickly.
- verify backend uses OpenAI-compatible base URL from `application-dev.properties`.

Note:
- this repository includes explicit HTTP/1.1 configuration for OpenAI API clients to improve compatibility with local model servers.

### 2) Wrong answers in business mode

Checklist:
- active profile is `narrow_rag` only when query is business-domain.
- business documents are ingested.
- vector metadata for business docs exists (`type=business-documents`).

If accuracy is still poor, run unit tests for deterministic metrics:
```bash
./gradlew test --tests io.jmix.ai.backend.retrieval.BusinessMetricsServiceTest
```

### 3) Evaluator errors in Answer Checks

If external evaluator is used (for example, hosted OpenAI):
- set `ANSWER_CHECKS_EVALUATOR_API_KEY`
- verify `answer-checks.evaluator.base-url`
- verify evaluator model availability

## Pre-push verification

Minimum:
```bash
./gradlew test
```

Optional targeted:
```bash
./gradlew test --tests io.jmix.ai.backend.chat.ChatImplTest
./gradlew test --tests io.jmix.ai.backend.retrieval.BusinessMetricsServiceTest
```
