(() => {
  "use strict";

  // Backend base URL. The API is served by this same Spring Boot app, so we
  // default to whatever origin the page itself was loaded from — this makes
  // the UI work unmodified whether it's opened via http://localhost:8080,
  // a LAN IP, or a public tunnel (Cloudflare, ngrok, etc.) pointed at the app.
  // The one case window.location.origin can't help with is opening this
  // file directly from disk (file://), so that falls back to localhost:8080.
  const API_BASE = window.location.protocol === "file:"
    ? "http://localhost:8080"
    : window.location.origin;

  const MODES = {
    chat: {
      title: "Standard Chat",
      desc: "Chatting directly with the model — no document context.",
      endpoint: "/chat",
      badge: null,
      emptyStateHtml:
        'Ask a question below to get started. Switch to <strong>PDF Knowledge Assistant</strong> mode to ask questions grounded in your uploaded documents.',
    },
    rag: {
      title: "PDF Knowledge Assistant",
      desc: "Answers are grounded in the PDFs you've ingested into the vector store, with sources cited below each answer.",
      endpoint: "/chat/rag",
      badge: "PDF Knowledge Assistant",
      emptyStateHtml:
        "Ask a question below to get started. Answers will be grounded in your ingested PDFs, with sources cited below each answer.",
    },
  };

  const state = {
    mode: "rag",
    locked: false,
  };

  // ---------- Element refs ----------

  const messagesEl = document.getElementById("messages");
  const emptyStateEl = document.getElementById("emptyState");
  const composerForm = document.getElementById("composerForm");
  const messageInput = document.getElementById("messageInput");
  const sendBtn = document.getElementById("sendBtn");
  const newChatBtn = document.getElementById("newChatBtn");
  const modeToggle = document.getElementById("modeToggle");
  const modeTitle = document.getElementById("modeTitle");
  const modeDesc = document.getElementById("modeDesc");
  const emptyStateTitle = document.getElementById("emptyStateTitle");
  const emptyStateText = document.getElementById("emptyStateText");
  const apiBaseLabel = document.getElementById("apiBaseLabel");

  const browseFolderBtn = document.getElementById("browseFolderBtn");
  const ingestProgressWrap = document.getElementById("ingestProgressWrap");
  const ingestProgressBar = document.getElementById("ingestProgressBar");
  const ingestStatus = document.getElementById("ingestStatus");
  const fileList = document.getElementById("fileList");

  const folderBrowserOverlay = document.getElementById("folderBrowserOverlay");
  const folderBrowserCloseBtn = document.getElementById("folderBrowserCloseBtn");
  const folderBrowserHomeBtn = document.getElementById("folderBrowserHomeBtn");
  const folderBrowserComputerBtn = document.getElementById("folderBrowserComputerBtn");
  const folderBrowserUpBtn = document.getElementById("folderBrowserUpBtn");
  const folderBrowserPath = document.getElementById("folderBrowserPath");
  const folderBrowserList = document.getElementById("folderBrowserList");
  const folderBrowserStatus = document.getElementById("folderBrowserStatus");
  const folderBrowserCancelBtn = document.getElementById("folderBrowserCancelBtn");
  const folderBrowserIngestBtn = document.getElementById("folderBrowserIngestBtn");

  apiBaseLabel.textContent = API_BASE.replace(/^https?:\/\//, "");

  // ---------- Textarea auto-resize + send button state ----------

  function autoResize() {
    messageInput.style.height = "auto";
    messageInput.style.height = Math.min(messageInput.scrollHeight, 200) + "px";
  }

  function updateSendState() {
    sendBtn.disabled = messageInput.value.trim().length === 0;
  }

  messageInput.addEventListener("input", () => {
    autoResize();
    updateSendState();
  });

  messageInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      composerForm.requestSubmit();
    }
  });

  // ---------- Mode toggle ----------

  function lockMode() {
    state.locked = true;
    modeToggle.classList.add("locked");
    modeToggle.querySelectorAll(".mode-option").forEach((el) => {
      el.disabled = true;
      el.title = "Start a new chat to switch modes";
    });
  }

  function unlockMode() {
    state.locked = false;
    modeToggle.classList.remove("locked");
    modeToggle.querySelectorAll(".mode-option").forEach((el) => {
      el.disabled = false;
      el.title = "";
    });
  }

  modeToggle.addEventListener("click", (e) => {
    if (state.locked) return;

    const btn = e.target.closest(".mode-option");
    if (!btn) return;

    const mode = btn.dataset.mode;
    if (mode === state.mode) return;

    state.mode = mode;

    modeToggle.querySelectorAll(".mode-option").forEach((el) => {
      el.classList.toggle("active", el.dataset.mode === mode);
    });

    modeTitle.textContent = MODES[mode].title;
    modeDesc.textContent = MODES[mode].desc;
    emptyStateTitle.textContent = MODES[mode].title;
    emptyStateText.innerHTML = MODES[mode].emptyStateHtml;
  });

  // ---------- New chat ----------

  newChatBtn.addEventListener("click", () => {
    messagesEl.innerHTML = "";
    messagesEl.appendChild(emptyStateEl);
    emptyStateEl.style.display = "";
    unlockMode();
  });

  // ---------- Chat message rendering ----------

  function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function hideEmptyState() {
    if (emptyStateEl.parentElement) {
      emptyStateEl.style.display = "none";
    }
  }

  function addMessageRow(role, { badge, typing } = {}) {
    hideEmptyState();

    const row = document.createElement("div");
    row.className = `message-row ${role}`;

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = role === "user" ? "U" : "AI";

    const content = document.createElement("div");
    content.className = "message-content";

    if (badge) {
      const badgeEl = document.createElement("span");
      badgeEl.className = "badge";
      badgeEl.textContent = badge;
      content.appendChild(badgeEl);
      content.appendChild(document.createElement("br"));
    }

    const textSpan = document.createElement("span");
    textSpan.className = "text";

    if (typing) {
      textSpan.innerHTML = '<span class="typing-dots"><span></span><span></span><span></span></span>';
    }

    content.appendChild(textSpan);
    row.appendChild(avatar);
    row.appendChild(content);
    messagesEl.appendChild(row);
    scrollToBottom();

    return { row, content, textSpan };
  }

  // Points a citation at the PDF it was pulled from, read straight off disk
  // by /documents/file (never copied), jumping to the cited page via the
  // browser's built-in PDF viewer's #page= fragment (1-based, same as the
  // page numbers the backend already reports).
  function buildSourceFileUrl(path, page) {
    const url = new URL(API_BASE + "/documents/file");
    url.searchParams.set("path", path);
    let href = url.toString();
    if (page != null) {
      href += `#page=${page}`;
    }
    return href;
  }

  function setMessageText(refs, text, isError, sources) {
    refs.textSpan.textContent = text;
    if (isError) {
      refs.content.classList.add("error");
    }

    if (sources && sources.length > 0) {
      const sourcesEl = document.createElement("div");
      sourcesEl.className = "sources";

      const label = document.createElement("div");
      label.className = "sources-label";
      label.textContent = "Sources";
      sourcesEl.appendChild(label);

      const list = document.createElement("ul");
      sources.forEach((s) => {
        const li = document.createElement("li");
        const label = s.page != null ? `${s.file} — page ${s.page}` : s.file;

        if (s.path) {
          const link = document.createElement("a");
          link.className = "source-link";
          link.textContent = label;
          link.href = buildSourceFileUrl(s.path, s.page);
          link.target = "_blank";
          link.rel = "noopener";
          link.title = `Open ${s.path}${s.page != null ? ` at page ${s.page}` : ""}`;
          li.appendChild(link);

          const pathEl = document.createElement("span");
          pathEl.className = "source-path";
          pathEl.textContent = s.path;
          li.appendChild(pathEl);
        } else {
          li.textContent = label;
        }

        list.appendChild(li);
      });
      sourcesEl.appendChild(list);

      refs.content.appendChild(sourcesEl);
    }

    scrollToBottom();
  }

  // ---------- Sending a chat message (stateless: no history sent) ----------

  async function sendMessage(question) {
    const mode = MODES[state.mode];

    if (!state.locked) {
      lockMode();
    }

    addMessageRow("user").textSpan.textContent = question;

    const assistantRefs = addMessageRow("assistant", {
      badge: mode.badge,
      typing: true,
    });

    try {
      const response = await fetch(API_BASE + mode.endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ q: question }),
      });

      const rawBody = await response.text();

      if (state.mode === "rag") {
        // /chat/rag returns JSON: { answer, sources: [{file, page, path}, ...] }
        let payload = null;
        try {
          payload = JSON.parse(rawBody);
        } catch (_) {
          // not JSON (e.g. an unexpected error page) — fall through to raw text below
        }

        if (!response.ok) {
          const message =
            (payload && (payload.error || payload.message)) ||
            rawBody ||
            `Request failed (${response.status})`;
          setMessageText(assistantRefs, message, true);
          return;
        }

        setMessageText(assistantRefs, payload ? payload.answer : rawBody, false, payload ? payload.sources : null);
        return;
      }

      // /chat returns plain text
      if (!response.ok) {
        setMessageText(assistantRefs, rawBody || `Request failed (${response.status})`, true);
        return;
      }

      setMessageText(assistantRefs, rawBody);
    } catch (err) {
      setMessageText(
        assistantRefs,
        `Couldn't reach the server at ${API_BASE}. Is the app running? (${err.message})`,
        true
      );
    }
  }

  composerForm.addEventListener("submit", (e) => {
    e.preventDefault();
    const question = messageInput.value.trim();
    if (!question) return;

    messageInput.value = "";
    autoResize();
    updateSendState();

    sendMessage(question);
  });

  // ---------- Document ingestion by directory path (reads PDFs in place off
  // disk; nothing is uploaded or duplicated, so citations carry the file's
  // real absolute path) ----------

  function addFileListItem(name) {
    const li = document.createElement("li");
    li.className = "file-item uploading";

    const icon = document.createElement("span");
    icon.className = "status-icon";
    icon.textContent = "⏳";

    const nameEl = document.createElement("span");
    nameEl.className = "name";
    nameEl.textContent = name;
    nameEl.title = name;

    li.appendChild(icon);
    li.appendChild(nameEl);
    fileList.prepend(li);

    return { li, icon };
  }

  function setFileItemState(item, status) {
    item.li.classList.remove("uploading", "success", "error");
    item.li.classList.add(status);
    item.icon.textContent = status === "success" ? "✓" : status === "error" ? "✕" : "⏳";
  }

  browseFolderBtn.addEventListener("click", () => {
    openFolderBrowser();
  });

  // ---------- In-app folder browser modal (backed by GET /documents/browse,
  // plain java.nio.file directory listing server-side — no OS dialog, no
  // AWT/Swing, nothing that depends on window-manager focus behavior) ----------

  let currentBrowsePath = null;   // absolute path currently listed, or null while viewing "Computer" (roots)
  let currentBrowseParent = null; // absolute path "Up" should go to, or null if there isn't one

  function openFolderBrowser() {
    folderBrowserOverlay.classList.remove("hidden");
    loadFolderListing({});
  }

  function closeFolderBrowser() {
    folderBrowserOverlay.classList.add("hidden");
  }

  folderBrowserCloseBtn.addEventListener("click", closeFolderBrowser);
  folderBrowserCancelBtn.addEventListener("click", closeFolderBrowser);

  folderBrowserOverlay.addEventListener("click", (e) => {
    if (e.target === folderBrowserOverlay) closeFolderBrowser();
  });

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !folderBrowserOverlay.classList.contains("hidden")) {
      closeFolderBrowser();
    }
  });

  folderBrowserHomeBtn.addEventListener("click", () => loadFolderListing({}));
  folderBrowserComputerBtn.addEventListener("click", () => loadFolderListing({ roots: true }));
  folderBrowserUpBtn.addEventListener("click", () => {
    if (currentBrowseParent) {
      loadFolderListing({ path: currentBrowseParent });
    } else {
      loadFolderListing({ roots: true });
    }
  });

  folderBrowserIngestBtn.addEventListener("click", async () => {
    if (!currentBrowsePath) return;
    closeFolderBrowser();
    await ingestDirectory(currentBrowsePath);
  });

  async function loadFolderListing({ path, roots }) {
    folderBrowserStatus.textContent = "Loading...";
    folderBrowserStatus.classList.remove("error");
    folderBrowserList.innerHTML = "";
    folderBrowserIngestBtn.disabled = true;

    try {
      const url = new URL(API_BASE + "/documents/browse");
      if (roots) {
        url.searchParams.set("roots", "true");
      } else if (path) {
        url.searchParams.set("path", path);
      }

      const response = await fetch(url);
      const rawBody = await response.text();

      let payload = {};
      try {
        payload = JSON.parse(rawBody);
      } catch (_) {
        // non-JSON response
      }

      if (!response.ok) {
        folderBrowserStatus.textContent = payload.error || rawBody || `Couldn't list that folder (${response.status}).`;
        folderBrowserStatus.classList.add("error");
        return;
      }

      currentBrowsePath = payload.path || null;
      currentBrowseParent = payload.parent || null;

      folderBrowserPath.textContent = currentBrowsePath || "Computer";
      folderBrowserUpBtn.disabled = currentBrowsePath === null;
      folderBrowserIngestBtn.disabled = currentBrowsePath === null;

      renderFolderEntries(payload.entries || []);
      folderBrowserStatus.textContent = "";
    } catch (err) {
      folderBrowserStatus.textContent = `Couldn't reach the server at ${API_BASE}. (${err.message})`;
      folderBrowserStatus.classList.add("error");
    }
  }

  function renderFolderEntries(entries) {
    folderBrowserList.innerHTML = "";

    if (entries.length === 0) {
      const empty = document.createElement("li");
      empty.className = "modal-empty";
      empty.textContent = "No subfolders here.";
      folderBrowserList.appendChild(empty);
      return;
    }

    entries.forEach((entry) => {
      const li = document.createElement("li");

      const row = document.createElement("button");
      row.type = "button";
      row.className = "modal-folder-row";

      const icon = document.createElement("span");
      icon.className = "folder-icon";
      icon.textContent = "\u{1F4C1}";

      const name = document.createElement("span");
      name.className = "folder-name";
      name.textContent = entry.name;
      name.title = entry.path;

      row.appendChild(icon);
      row.appendChild(name);

      if (entry.pdfCount > 0) {
        const count = document.createElement("span");
        count.className = "pdf-count";
        count.textContent = `${entry.pdfCount} PDF${entry.pdfCount > 1 ? "s" : ""}`;
        row.appendChild(count);
      }

      row.addEventListener("click", () => loadFolderListing({ path: entry.path }));

      li.appendChild(row);
      folderBrowserList.appendChild(li);
    });
  }

  async function ingestDirectory(directory) {
    ingestProgressWrap.classList.remove("hidden");
    ingestProgressBar.style.width = "100%";
    ingestStatus.textContent = `Scanning ${directory}...`;
    ingestStatus.classList.remove("error");

    try {
      const response = await fetch(API_BASE + "/documents/ingest-directory", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ directory }),
      });

      const rawBody = await response.text();
      let payload = {};
      try {
        payload = JSON.parse(rawBody);
      } catch (_) {
        // non-JSON response
      }

      if (!response.ok) {
        ingestStatus.textContent = payload.error || rawBody || `Ingest failed (${response.status}).`;
        ingestStatus.classList.add("error");
        return;
      }

      (payload.ingested || []).forEach((name) => {
        setFileItemState(addFileListItem(name), "success");
      });
      (payload.failed || []).forEach((f) => {
        const item = addFileListItem(f.file);
        item.li.title = f.error;
        setFileItemState(item, "error");
      });

      ingestStatus.textContent =
        payload.message ||
        `Added ${payload.chunksAdded ?? "?"} chunks from ${payload.filesProcessed ?? "?"} file(s).`;
    } catch (err) {
      ingestStatus.textContent = `Couldn't reach the server at ${API_BASE}. (${err.message})`;
      ingestStatus.classList.add("error");
    } finally {
      setTimeout(() => {
        ingestProgressWrap.classList.add("hidden");
      }, 1200);
    }
  }

  // ---------- Init ----------

  updateSendState();
  autoResize();
})();
