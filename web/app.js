// POCW Search web client: search over WebSocket with live pipeline stages, admin over REST.
// All server data is inserted with textContent / DOM nodes only (no innerHTML) to prevent XSS.
"use strict";

const $ = (id) => document.getElementById(id);
const wsUrl = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws/search";
let ws = null;
let pending = null; // {query, userTerms, expTerms, t0}

function pill(id, text, cls) {
  const el = $(id);
  el.textContent = text;
  el.className = "pill " + (cls || "");
}

async function refreshHealth() {
  try {
    const h = await (await fetch("/api/health")).json();
    pill("st-server", "сервер ok", "ok");
    pill("st-ollama", h.ollama ? "ollama ok" : "ollama недоступна", h.ollama ? "ok" : "warn");
    pill("st-index", h.indexing ? "індексація…" : h.index_ready ? "індекс готовий" : "індекс не побудовано",
      h.indexing ? "warn" : h.index_ready ? "ok" : "bad");
  } catch {
    pill("st-server", "сервер недоступний", "bad");
  }
}

function connect() {
  ws = new WebSocket(wsUrl);
  pill("st-ws", "ws підключення…", "warn");
  ws.onopen = () => pill("st-ws", "ws підключено", "ok");
  ws.onclose = () => {
    pill("st-ws", "ws відключено", "bad");
    if (pending) finish();
    setTimeout(connect, 1500); // the server closes idle sessions; reconnect quietly
  };
  ws.onmessage = (ev) => onStage(JSON.parse(ev.data));
}

function setStage(stage, cls, text) {
  const li = document.querySelector(`.pipeline li[data-stage="${stage}"]`);
  li.className = cls;
  li.querySelector("small").textContent = text || "";
}

function resetPipeline() {
  for (const li of document.querySelectorAll(".pipeline li")) {
    li.className = "";
    li.querySelector("small").textContent = "";
  }
}

function onStage(msg) {
  if (!pending) return;
  const d = msg.data || {};
  switch (msg.stage) {
    case "query_expansion": {
      pending.userTerms = d.query_terms || [];
      pending.expTerms = d.expanded_terms || [];
      const skipped = !pending.expTerms.length;
      setStage("query_expansion", skipped ? "done skipped" : "done",
        `${d.time_ms} мс · ${d.source}`);
      setStage("keyword_search", "active");
      showTerms(pending.userTerms, pending.expTerms, d.source);
      break;
    }
    case "keyword_search":
      setStage("keyword_search", "done", `${d.time_ms} мс`);
      setStage("done", "active");
      showResults(d.results || []);
      break;
    case "done":
      setStage("done", "done", `${d.total_time_ms ?? Math.round(performance.now() - pending.t0)} мс`);
      finish();
      break;
    case "error":
      showMessage(`Помилка ${d.code}: ${d.message}`);
      resetPipeline();
      finish();
      break;
  }
}

function finish() {
  pending = null;
  $("go").disabled = false;
  refreshHealth();
}

function showMessage(text) {
  const m = $("message");
  m.textContent = text;
  m.hidden = !text;
}

function showTerms(user, exp, source) {
  const box = $("terms");
  box.replaceChildren();
  const label = document.createElement("span");
  label.textContent = "Терми:";
  box.append(label);
  for (const t of user) box.append(chip(t, "chip user"));
  for (const t of exp) box.append(chip(t, "chip"));
  if (!exp.length && source !== "off") {
    const note = document.createElement("span");
    note.textContent = `(розширення: ${source})`;
    box.append(note);
  }
  box.hidden = false;
}

function chip(text, cls) {
  const s = document.createElement("span");
  s.className = cls;
  s.textContent = text;
  return s;
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Splits text on whole-word matches and wraps them in <mark>, using DOM nodes only.
function highlighted(text, user, exp) {
  const frag = document.createDocumentFragment();
  const all = [...user, ...exp];
  if (!all.length) {
    frag.append(text);
    return frag;
  }
  const re = new RegExp(`\\b(${all.map(escapeRegex).join("|")})\\b`, "gi");
  let last = 0;
  for (const m of text.matchAll(re)) {
    frag.append(text.slice(last, m.index));
    const mark = document.createElement("mark");
    if (!user.includes(m[0].toLowerCase())) mark.className = "exp";
    mark.textContent = m[0];
    frag.append(mark);
    last = m.index + m[0].length;
  }
  frag.append(text.slice(last));
  return frag;
}

function showResults(results) {
  const box = $("results");
  box.replaceChildren();
  const sum = document.createElement("div");
  sum.className = "summary";
  sum.textContent = results.length ? `Знайдено топ-${results.length}` : "Нічого не знайдено";
  box.append(sum);
  for (const r of results) {
    const div = document.createElement("div");
    div.className = "result";
    const h = document.createElement("h3");
    h.append(r.doc_id);
    const sc = document.createElement("span");
    sc.className = "score";
    const bar = document.createElement("span");
    bar.className = "bar";
    bar.style.width = `${Math.max(4, Math.round(r.score * 80))}px`;
    sc.append(bar, ` ${r.score.toFixed(3)} · BM25 ${r.bm25}`);
    h.append(sc);
    const p = document.createElement("p");
    p.append(highlighted(r.snippet || "", pending.userTerms, pending.expTerms));
    const mt = document.createElement("div");
    mt.className = "matched";
    mt.textContent = "збіги: " + (r.matched_terms || []).join(", ");
    div.append(h, p, mt);
    box.append(div);
  }
}

$("search-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const query = $("q").value.trim();
  if (!query || pending) return;
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    showMessage("WebSocket не підключено, спробуйте за мить");
    return;
  }
  showMessage("");
  $("results").replaceChildren();
  $("terms").hidden = true;
  resetPipeline();
  const expand = $("expand").checked;
  setStage("query_expansion", expand ? "active" : "", expand ? "" : "вимкнено");
  pending = { query, userTerms: [], expTerms: [], t0: performance.now() };
  $("go").disabled = true;
  ws.send(JSON.stringify({ query, expand }));
});

$("index-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const btn = $("build");
  btn.disabled = true;
  btn.textContent = "Будується…";
  pill("st-index", "індексація…", "warn");
  try {
    const resp = await fetch("/api/index", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ threads: Number($("threads").value), impl: $("impl").value }),
    });
    const body = await resp.json();
    $("stats").textContent = JSON.stringify(body, null, 2);
  } catch (err) {
    $("stats").textContent = String(err);
  } finally {
    btn.disabled = false;
    btn.textContent = "Побудувати індекс";
    refreshHealth();
  }
});

$("admin").addEventListener("toggle", async () => {
  if ($("admin").open) {
    $("stats").textContent = JSON.stringify(await (await fetch("/api/stats")).json(), null, 2);
  }
});

refreshHealth();
setInterval(refreshHealth, 10000);
connect();
