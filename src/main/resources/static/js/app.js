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

  const ingestUrlForm = document.getElementById("ingestUrlForm");
  const ingestUrlInput = document.getElementById("ingestUrlInput");
  const ingestProgressWrap = document.getElementById("ingestProgressWrap");
  const ingestProgressBar = document.getElementById("ingestProgressBar");
  const ingestStatus = document.getElementById("ingestStatus");
  const fileList = document.getElementById("fileList");

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

        if (s.sourceUrl) {
          // Ingested via /documents/ingest-url (or the matching chat tool) -- link
          // straight to the original URL the PDF was downloaded from.
          const link = document.createElement("a");
          link.className = "source-link";
          link.textContent = label;
          link.href = s.page != null ? `${s.sourceUrl}#page=${s.page}` : s.sourceUrl;
          link.target = "_blank";
          link.rel = "noopener";
          link.title = `Open ${s.sourceUrl}${s.page != null ? ` at page ${s.page}` : ""}`;
          li.appendChild(link);

          const urlEl = document.createElement("span");
          urlEl.className = "source-path";
          urlEl.textContent = s.sourceUrl;
          li.appendChild(urlEl);
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
        // /chat/rag returns JSON: { answer, sources: [{file, page, sourceUrl}, ...] }
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

  // ---------- Document ingestion by URL (the server downloads the PDF
  // itself, so this works for anyone hitting the app from any machine) ----------

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

  ingestUrlForm.addEventListener("submit", (e) => {
    e.preventDefault();
    const url = ingestUrlInput.value.trim();
    if (!url) return;

    ingestUrl(url);
  });

  async function ingestUrl(url) {
    ingestProgressWrap.classList.remove("hidden");
    ingestProgressBar.style.width = "100%";
    ingestStatus.textContent = `Fetching ${url}...`;
    ingestStatus.classList.remove("error");

    try {
      const response = await fetch(API_BASE + "/documents/ingest-url", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
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

      setFileItemState(addFileListItem(payload.file || url), "success");
      // Always show the actual chunk count -- a message alone ("Document ingested
      // from URL") can't be told apart from a 0-chunk no-op, which is exactly the
      // failure mode that's easy to miss.
      ingestStatus.textContent =
        `Added ${payload.chunksAdded ?? "?"} chunk(s) from ${payload.file ?? url}.`;
      ingestUrlInput.value = "";
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
