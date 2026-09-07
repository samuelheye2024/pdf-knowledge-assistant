# PDF Knowledge Assistant

Spring Boot + Spring AI app that exposes a chat API (plain and RAG-over-PDFs) backed by a Postgres/pgvector vector store, plus a ChatGPT-style web UI.

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
![Standard Chat](screenshots/standard-chat.png)

### PDF Knowledge Assistant (RAG with sources)
![PDF Knowledge Assistant](screenshots/pdf-knowledge-assistant.png)

### Document upload in progress
![Document upload in progress](screenshots/document-upload.png)

## UI features

- **Standard Chat** mode — talks directly to the model (`POST /chat`).
- **PDF Knowledge Assistant** mode — RAG over your uploaded PDFs (`POST /chat/rag`), with a **Sources** list (file + page) shown under each answer.
- **Upload PDFs** from the sidebar — files are chunked, embedded, and added to the pgvector-backed vector store (`POST /documents`). A non-blocking progress bar shows upload status; you can keep chatting while an upload is in progress.
- Mode is locked once you send your first message in a chat — start a **New Chat** to switch between Standard Chat and Knowledge Assistant.
- Stateless: each question is sent independently, no conversation history is kept server-side.

Note: the vector store is backed by Postgres/pgvector — uploaded documents persist across app restarts.

## API endpoints

| Method | Path         | Body                                                  | Response                                                          |
|--------|--------------|--------------------------------------------------------|--------------------------------------------------------------------|
| POST   | `/chat`      | `{"q": "..."}`                                          | plain text answer                                                  |
| POST   | `/chat/rag`  | `{"q": "..."}`                                          | `{"answer": "...", "sources": [{"file": "...", "page": 1}, ...]}`  |
| POST   | `/documents` | multipart form, repeatable `files` field (PDFs only)    | `{"message": "...", "filesProcessed": N, "chunksAdded": N}`        |

`/chat/rag`'s `sources` are the actual document chunks retrieved from the vector store for that question (deduped by file + page) — not something the model is asked to guess, so they're always accurate to what was fed into the prompt.

A Postman collection (`src/main/resources/postman-collection/pdf-knowledge-assistant.postman_collection.json`) is included for testing these directly.

## Environment

Set your OpenAI API key before starting the app:

```
export OPENAI_API_KEY=sk-...
```

Postgres connection settings (`spring.datasource.*` and `spring.ai.vectorstore.pgvector.*`) live in `src/main/resources/application.properties` and can be overridden with the usual Spring Boot env vars/flags.
