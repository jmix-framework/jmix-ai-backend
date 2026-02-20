# AGENTS.md

Guide for coding agents working in `jmix-ai-backend`.

## Skills and MCP

- For detailed guidance on specific Jmix features, ALWAYS use the Skill tool and available Jmix skills.
- If you don't have enough information, use Context7 MCP to search for Jmix documentation and code samples.
- Use Jetbrains MCP to check file problems with `get_file_problems("path/to/file.ext", onlyErrors=false)`

## Project Snapshot
- Stack: Java 17, Jmix 2.7 (Spring Boot 3, Vaadin Flow UI), Spring AI.
- Build tool: Gradle (`./gradlew`).
- Main app: `src/main/java/io/jmix/ai/backend`.
- Sidecar Python service `reranker/` (document reranking, FastAPI, port `8000`)
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

## Reranker Service
```bash
cd reranker
python3.10 -m venv env
source env/bin/activate
pip install -r requirements.txt
uvicorn reranker_service:app --host 0.0.0.0 --port 8000
```

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

## Development Guidelines

Refer to the relevant skills for detailed implementation patterns.

### Working with Entities

- JPA entities: use `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName`
- Relationships: Use `@Composition` for parent-child aggregates
- Computed properties: Use `@JmixProperty` with `@DependsOnProperties` for caching expensive calculations
- No Lombok on entities

### Working with Services

- Injection: Use constructor injection, not field injection
- [Data Access](#data-access)

### Data Access

- Data access: Use `DataManager` (NOT `EntityManager`) and its fluent data loading interface for queries (see jmix-services skill))
- Fetch plans: Build optimized FetchPlans to avoid N+1 queries (see jmix-fetch-plans skill))
- Transactions: Annotate with `@Transactional` when needed

### Working with Views

- View descriptors: XML files in `src/main/resources/**/view/**`
- Controllers: Java classes with `@ViewController` and `@ViewDescriptor` annotations, extend `StandardListView` / `StandardDetailView`
- Navigation: Use `ViewNavigators` for programmatic navigation between views

### Working with Security

- Resource roles: Define as interfaces annotated with `@ResourceRole` in `security/` package and add policy annotations on methods
- Entity policies: Use `@EntityPolicy` for CRUD operations
- Attribute policies: Use `@EntityAttributePolicy` for field-level access
- View/Menu policies: Use `@ViewPolicy` and `@MenuPolicy` for UI access control

### Database Migrations

Liquibase changelogs are in `src/main/resources/**/liquibase/changelog/**.xml`:
- Organized by step numbers (`010-some-description.xml`, `020-other-description.xml`, etc.) or in hieracrhical time-based structure (`2026/02/19-105244-customer.xml`, `2026/02/20-120315-order.xml`)
- Include new changelogs to the main `changelog.xml`
- Run automatically on application startup

### Patterns

- Business logic in services, not in views
- Dependency Injection
  - Views: `@ViewComponent` for components defined in XML (visual components, data containers, data loaders, MessageBundle, DataContext)
  - Views: `@Autowired` for Spring beans (DataManager, DialogWindows, etc.)
  - Services: Constructor injection only

### When Asked to Create

#### Entity

- Java class with UUID + Version + InstanceName
- Liquibase changelog + include in `changelog.xml`
- Messages in ALL locale files (`messages.properties`, `messages_*.properties`)

#### View

- XML descriptor + Java controller
- Menu entry in `menu.xml`
- Messages for title/labels in ALL locale files

#### Role

- `@ResourceRole` with entity/view/menu policies

### Forbidden

- Lombok on entities
- Field `@Autowired` in services (use constructor injection)
- EntityManager
- Business logic in views
- Edits in `frontend/generated/`
- Hardcoded UI text — ALL labels, titles, buttons MUST use `msg://` keys
- Single-locale messages — ALWAYS add to ALL locale files
- Irrelevant edits:
  - Do not add blank lines at the end of edited files.

## Development Workflow

After writing or modifying code, validate using this sequence:

1. **Check file problems** — if `jetbrains` MCP available, use it to check file problems for each modified file with `get_file_problems("path/to/file.ext", onlyErrors=false)`
2. **Write tests** — create/update tests for new functionality
3. **Run tests** — `./gradlew test` to verify nothing is broken
4. **UI verification** (for views) — if `playwright` MCP available and app is running:
   - Navigate to the view
   - Verify data displays correctly
   - Click or do things that should trigger UI logic
   - Test CRUD operations
