# PDF Knowledge Assistant

Spring Boot + Spring AI app that exposes a chat API (plain and RAG-over-PDFs) backed by a Postgres/pgvector vector store, plus a ChatGPT-style web UI. Documents are ingested by pointing the app at a folder already on disk — picked via a native OS folder dialog, not a browser upload — so RAG citations can point at a document's real absolute location.

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

Note: the screenshots above show an earlier version of the sidebar with a browser-upload control; documents are now ingested via a native folder-picker dialog instead (see below).

## UI features

- **Standard Chat** mode — talks directly to the model (`POST /chat`).
- **PDF Knowledge Assistant** mode — RAG over your ingested PDFs (`POST /chat/rag`), with a **Sources** list (file, page, and absolute path) shown under each answer.
- **Browse Folder…** from the sidebar — opens your OS's native folder-picker (`POST /documents/browse-dialog`), the same kind of dialog VS Code's "Open Folder" uses. Once you pick a folder, every PDF under it (recursively, by default) is read in place and added to the vector store (`POST /documents/ingest-directory`). Nothing is copied or uploaded: the app reads each file straight from where it already lives, so citations carry the document's real absolute path.
- Mode is locked once you send your first message in a chat — start a **New Chat** to switch between Standard Chat and Knowledge Assistant.
- Stateless: each question is sent independently, no conversation history is kept server-side.

Note: the vector store is backed by Postgres/pgvector — ingested documents persist across app restarts. The native folder dialog requires this app to be running on your own desktop with a display attached — it won't work if you run it headless or in a container (there's no screen for the dialog to appear on), and it needs `java.awt.headless` forced to `false`, which `SpringAiApplication.main()` does directly (setting it via `application.properties` doesn't work — Spring Boot locks in AWT headless mode before it even reads that file).

## API endpoints

| Method | Path                          | Body                                             | Response                                                                                                             |
|--------|-------------------------------|---------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| POST   | `/chat`                       | `{"q": "..."}`                                     | plain text answer                                                                                                        |
| POST   | `/chat/rag`                   | `{"q": "..."}`                                     | `{"answer": "...", "sources": [{"file": "...", "page": 1, "path": "..."}, ...]}`                                         |
| POST   | `/documents/browse-dialog`    | *(none)*                                           | `{"directory": "/abs/path"}` or `{"cancelled": true}`                                                                    |
| POST   | `/documents/ingest-directory` | `{"directory": "/abs/path", "recursive": true}`    | `{"message": "...", "directory": "...", "filesProcessed": N, "chunksAdded": N, "ingested": [...], "failed": [...]}`      |

`/chat/rag`'s `sources` are the actual document chunks retrieved from the vector store for that question (deduped by file + page) — not something the model is asked to guess, so they're always accurate to what was fed into the prompt, and `path` is the file's real absolute location on disk. `/documents/browse-dialog` pops a native OS folder-picker on the machine running the app and hands back the chosen absolute path — a plain web page can't do this on its own (browsers never expose real filesystem paths to JavaScript, by design), so this endpoint uses AWT's `FileDialog` server-side, where there's no such sandbox. `/documents/ingest-directory` then reads each PDF directly from that folder in place; nothing is copied or uploaded. `recursive` defaults to `true` when omitted. Re-ingesting the same file adds duplicate chunks rather than replacing the old ones — there's no dedup/upsert yet.

A Postman collection (`src/main/resources/postman-collection/pdf-knowledge-assistant.postman_collection.json`) is included for testing these directly.

## Environment

Set your OpenAI API key before starting the app:

```
export OPENAI_API_KEY=sk-...
```

Postgres connection settings (`spring.datasource.*` and `spring.ai.vectorstore.pgvector.*`) live in `src/main/resources/application.properties` and can be overridden with the usual Spring Boot env vars/flags.
