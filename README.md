# PDF Knowledge Assistant

Spring Boot + Spring AI app that exposes a chat API (plain and RAG-over-PDFs) backed by a Postgres/pgvector vector store, plus a ChatGPT-style web UI. Documents are ingested by pointing the app at a folder already on disk — browsed via an in-app folder browser, not a browser upload — so RAG citations can point at a document's real absolute location.

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

### PDF Ingestion
![PDF Ingestion](screenshots/1.%20PDF%20Ingestion.png)

### Chat Response with Sources
![Chat Response with Sources](screenshots/2.%20Chat%20response%20with%20sources.png)

### Ingesting PDFs via Chat
![Ingesting PDFs via Chat](screenshots/3.%20Ingesting%20PDFs%20via%20Chat,%20using%20pdf%20ingestion%20tool.png)

### pgvector Vector Store
![pgvector Vector Store](screenshots/pgvector%20-%20vector%20store.png)

## UI features

- **Standard Chat** mode — talks directly to the model (`POST /chat`).
- **PDF Knowledge Assistant** mode — RAG over your ingested PDFs (`POST /chat/rag`), with a **Sources** list (file, page, and absolute path) shown under each answer. Each citation is a clickable link (backed by `GET /documents/file`) that opens the actual PDF, straight off disk, at the cited page — nothing is copied or uploaded to show it.
- **Browse Folder…** from the sidebar — opens an in-app folder browser (a modal backed by `GET /documents/browse`, which lists subfolders with plain `java.nio.file` directory listing, no OS dialog involved). Click through folders (or jump to **Home** / **Computer**, or go **Up**) and hit **Ingest This Folder** on whichever one you land on. Every PDF under it (recursively, by default) is then read in place and added to the vector store (`POST /documents/ingest-directory`). Nothing is copied or uploaded: the app reads each file straight from where it already lives, so citations carry the document's real absolute path.
- Inside **PDF Knowledge Assistant** mode, you can also just ask for it in the chat box — e.g. *"please ingest the PDFs in ~/Documents/legal"*. This is a Spring AI [`@Tool`](https://docs.spring.io/spring-ai/reference/api/tools.html) (`IngestionTools.ingestDirectory`) made available to `/chat/rag` alone (a per-request tool, not a client-wide default), so the model calls it when it recognizes an ingestion request; it shares the exact same `IngestionService` as the folder-browser button and the REST endpoint, so all three paths behave identically. `~` is expanded to your home directory since that's how paths are naturally typed. **Standard Chat** mode never sees this tool. This is an alternative to the button, not a replacement for it.
- Mode is locked once you send your first message in a chat — start a **New Chat** to switch between Standard Chat and Knowledge Assistant.
- Stateless: each question is sent independently, no conversation history is kept server-side.

Note: the vector store is backed by Postgres/pgvector — ingested documents persist across app restarts. The folder browser works identically on Windows, macOS, and Linux — it's rendered entirely in the browser from a plain directory listing, so there's no native dialog, no AWT/Swing, and no window-manager focus behavior to depend on.

## API endpoints

| Method | Path                          | Query / Body                                       | Response                                                                                                             |
|--------|-------------------------------|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| POST   | `/chat`                       | `{"q": "..."}`                                         | plain text answer                                                                                                        |
| POST   | `/chat/rag`                   | `{"q": "..."}`                                         | `{"answer": "...", "sources": [{"file": "...", "page": 1, "path": "..."}, ...]}`                                         |
| GET    | `/documents/browse`           | `?path=/abs/path` or `?roots=true` (both optional)     | `{"path": "...", "parent": "...", "entries": [{"name": "...", "path": "...", "pdfCount": N}, ...]}`                      |
| POST   | `/documents/ingest-directory` | `{"directory": "/abs/path", "recursive": true}`        | `{"message": "...", "directory": "...", "filesProcessed": N, "chunksAdded": N, "ingested": [...], "failed": [...]}`      |
| GET    | `/documents/file`             | `?path=/abs/path/to/file.pdf`                          | the PDF's raw bytes, `Content-Type: application/pdf`, `Content-Disposition: inline`                                      |

`/chat/rag`'s `sources` are the actual document chunks retrieved from the vector store for that question (deduped by file + page) — not something the model is asked to guess, so they're always accurate to what was fed into the prompt, and `path` is the file's real absolute location on disk. `/documents/browse` lists a directory's subfolders (omit `path` for the home directory, or pass `roots=true` to list filesystem roots — drive letters on Windows, `/` on macOS/Linux) using plain `java.nio.file` calls; hidden and unreadable entries are filtered out, and each entry's `pdfCount` is a non-recursive count of PDFs directly inside it. `/documents/ingest-directory` then reads each PDF directly from the chosen folder in place; nothing is copied or uploaded. `recursive` defaults to `true` when omitted. Re-ingesting the same file adds duplicate chunks rather than replacing the old ones — there's no dedup/upsert yet. `/documents/file` streams a cited PDF straight off disk (also in place, never copied) so the UI can link each citation directly to its source, jumping to the cited page; it trusts whatever path it's given as long as it's an existing, readable `.pdf` file, which is fine for a personal, localhost-only tool but not something to expose beyond that without adding access checks.

A Postman collection (`src/main/resources/postman-collection/pdf-knowledge-assistant.postman_collection.json`) is included for testing these directly.

## Environment

Set your OpenAI API key before starting the app:

```
export OPENAI_API_KEY=sk-...
```

Postgres connection settings (`spring.datasource.*` and `spring.ai.vectorstore.pgvector.*`) live in `src/main/resources/application.properties` and can be overridden with the usual Spring Boot env vars/flags.

Pinned to Spring AI `1.0.0-M6` (bumped from `1.0.0-M3`) — the minimum milestone with the `@Tool` annotation used by `IngestionTools`, chosen specifically because it still uses the same starter artifact ids this project already depends on and still targets Spring Boot 3.x (later Spring AI lines rename the starters and, at `2.0.x`, require Spring Boot 4). One related API change came with it: `SearchRequest` is now built via `SearchRequest.builder()...build()` rather than the old `SearchRequest.query(...).withTopK(...)` static factory.
