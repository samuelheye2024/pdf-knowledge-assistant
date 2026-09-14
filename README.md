# PDF Knowledge Assistant

Spring Boot + Spring AI app that exposes a chat API (plain and RAG-over-PDFs) backed by a Postgres/pgvector vector store, plus a ChatGPT-style web UI. Documents get in by handing the app a public URL to a PDF, which the server downloads and ingests itself -- this works for anyone hitting the app, from any machine, since the server does the fetching rather than reading a path off its own disk.

## Postgres + pgvector setup

The vector store needs a Postgres instance with the `pgvector` extension enabled. On macOS with Homebrew:

```
brew install postgresql
brew services start postgresql
createuser -s postgres
brew install --cask dbeaver-community
brew install pgvector
```

`dbeaver-community` is a free SQL client, handy for poking at the `vector_store` table; `pgvector` installs the extension itself. Then, connected to the target database (`psql -U postgres` or via DBeaver), enable the extension:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- Verify extension
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

The app's `spring.ai.vectorstore.pgvector.initialize-schema=true` setting also runs `CREATE EXTENSION IF NOT EXISTS vector` on startup, so this is mainly useful to confirm the extension is actually installed and available before running the app.

## Running

Reachable at the URL/credentials configured in `src/main/resources/application.properties` (defaults to `jdbc:postgresql://localhost:5432/postgres`, user `postgres`, no password). The `vector_store` table is created automatically on startup.

```
./gradlew bootRun
```

The app starts on **http://localhost:8080**. The UI is served automatically at that same address (`src/main/resources/static/`) — just open http://localhost:8080 in a browser.

## Screenshots

### Standard Chat
![Standard Chat](screenshots/1.%20%20Standard%20Chat.png)

### PDF Knowledge Assistant
![PDF Knowledge Assistant](screenshots/2.%20PDF%20Knowledge%20Assistant.png)

### Vector Store
![Vector Store](screenshots/3.%20vector_store.png)

### Content
![Content](screenshots/4.%20Content.png)

### Metadata
![Metadata](screenshots/5.%20Metadata.png)

### Embeddings
![Embeddings](screenshots/6.%20Embeddings.png)

## UI features

- **Standard Chat** mode — talks directly to the model (`POST /chat`).
- **PDF Knowledge Assistant** mode — RAG over your ingested PDFs (`POST /chat/rag`), with a **Sources** list (file, page, and source URL) shown under each answer. Each citation is a clickable link straight to the URL the PDF was downloaded from. When a question is actually an ingestion request handled by the `ingestUrl` tool rather than answered from the knowledge base, the response has no sources -- there's nothing to cite.
- **Ingest a PDF from a URL**, in the sidebar — downloads a single PDF from a public `http(s)` URL and ingests it (`POST /documents/ingest-url`). This works for anyone, from any machine, since the server does the fetching itself.
- Inside **PDF Knowledge Assistant** mode, you can also just ask for this in the chat box — e.g. *"please ingest https://example.com/paper.pdf"*. This is a Spring AI [`@Tool`](https://docs.spring.io/spring-ai/reference/api/tools.html) method (`IngestionTools.ingestUrl`) made available to `/chat/rag` alone (a per-request tool, not a client-wide default), so the model calls it when it recognizes an ingestion request; it shares the exact same `IngestionService` as the sidebar's URL field and REST endpoint, so behavior never diverges. **Standard Chat** mode never sees this tool. It's an alternative to the sidebar field, not a replacement for it.
- Mode is locked once you send your first message in a chat — start a **New Chat** to switch between Standard Chat and Knowledge Assistant.
- Stateless: each question is sent independently, no conversation history is kept server-side.

Note: the vector store is backed by Postgres/pgvector — ingested documents persist across app restarts.

## API endpoints

| Method | Path                    | Query / Body                       | Response                                                                                          |
|--------|-------------------------|-------------------------------------|-----------------------------------------------------------------------------------------------------|
| POST   | `/chat`                 | `{"q": "..."}`                       | plain text answer                                                                                    |
| POST   | `/chat/rag`             | `{"q": "..."}`                       | `{"answer": "...", "sources": [{"file": "...", "page": 1, "sourceUrl": "..."}, ...]}`                |
| POST   | `/documents/ingest-url` | `{"url": "https://.../paper.pdf"}`   | `{"message": "...", "url": "...", "file": "...", "chunksAdded": N}`                                  |

`/chat/rag`'s `sources` are the actual document chunks retrieved from the vector store for that question (deduped by file + page) — not something the model is asked to guess, so they're always accurate to what was fed into the prompt. `sources` comes back empty whenever the model instead ran the `ingestUrl` tool for that turn, since the retrieved chunks weren't what the reply was actually about. `/documents/ingest-url` downloads a single PDF from a public URL and adds it to the vector store — the server does the fetching itself, so this works identically for a caller on any machine. Because it fetches a URL handed to it by whoever calls the endpoint, it refuses to fetch loopback/private/link-local addresses (a basic SSRF guard), caps the download at 25 MB, and checks the downloaded bytes actually start with a PDF's magic header rather than trusting `Content-Type`. Re-ingesting the same URL adds duplicate chunks rather than replacing the old ones — there's no dedup/upsert yet.

A Postman collection (`src/main/resources/postman-collection/pdf-knowledge-assistant.postman_collection.json`) is included for testing these directly.

## Environment

Set your OpenAI API key before starting the app:

```
export OPENAI_API_KEY=sk-...
```

Postgres connection settings (`spring.datasource.*` and `spring.ai.vectorstore.pgvector.*`) live in `src/main/resources/application.properties` and can be overridden with the usual Spring Boot env vars/flags.

Pinned to Spring AI `1.0.0-M6` (bumped from `1.0.0-M3`) — the minimum milestone with the `@Tool` annotation used by `IngestionTools`, chosen specifically because it still uses the same starter artifact ids this project already depends on and still targets Spring Boot 3.x (later Spring AI lines rename the starters and, at `2.0.x`, require Spring Boot 4). One related API change came with it: `SearchRequest` is now built via `SearchRequest.builder()...build()` rather than the old `SearchRequest.query(...).withTopK(...)` static factory.
